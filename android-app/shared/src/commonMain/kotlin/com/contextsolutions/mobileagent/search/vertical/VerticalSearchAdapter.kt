package com.contextsolutions.mobileagent.search.vertical

import com.contextsolutions.mobileagent.preferences.GpsCoordinates
import com.contextsolutions.mobileagent.preferences.UserLocation
import com.contextsolutions.mobileagent.preferences.VerticalPreferences
import com.contextsolutions.mobileagent.search.SearchOutcome
import com.contextsolutions.mobileagent.search.SearchSubtype

/**
 * Fetches a per-vertical answer for one user turn. The result is shaped
 * like a Brave search outcome so the agent loop's existing
 * Assistant(toolCall) + Tool(result) injection path can absorb it without
 * branching on subtype.
 *
 * Implementations:
 *  - [BraveSiteFilterAdapter] — NEWS / SPORTS (reuses Brave with `site:` filter)
 *  - [FeedAdapter] — WEATHER / FINANCE (RSS / HTML / JSON per
 *    [com.contextsolutions.mobileagent.preferences.SiteConfig.kind])
 *  - GENERAL is dispatched directly to [com.contextsolutions.mobileagent.search.SearchService]
 *    by [VerticalSearchDispatcher] without going through this interface.
 *
 * All implementations MUST move HTTP + parsing onto `Dispatchers.IO` per
 * invariant #1.
 *
 * [gps] is the user's captured-city coordinates (from [LocationCatalog]).
 * Weather adapters substitute them into URL templates like
 * `weather.gc.ca/.../?coords={lat},{lon}`. Null when the user hasn't
 * picked a city yet or the city isn't in the bundled catalog; adapters
 * that need GPS skip those sources and fall through to the next entry.
 */
interface VerticalSearchAdapter {
    suspend fun fetch(
        query: String,
        prefs: VerticalPreferences,
        location: UserLocation?,
        gps: GpsCoordinates? = null,
    ): SearchOutcome
}

/**
 * Dispatches one search request to the adapter that handles its
 * [SearchSubtype]. GENERAL takes the legacy Brave path; the four verticals
 * each delegate to a registered [VerticalSearchAdapter].
 */
class VerticalSearchDispatcher(
    private val adapters: Map<SearchSubtype, VerticalSearchAdapter>,
    private val generalAdapter: VerticalSearchAdapter,
) {
    suspend fun fetch(
        subtype: SearchSubtype,
        query: String,
        prefs: VerticalPreferences,
        location: UserLocation?,
        gps: GpsCoordinates? = null,
    ): SearchOutcome {
        val adapter = adapters[subtype] ?: generalAdapter
        return adapter.fetch(query = query, prefs = prefs, location = location, gps = gps)
    }
}
