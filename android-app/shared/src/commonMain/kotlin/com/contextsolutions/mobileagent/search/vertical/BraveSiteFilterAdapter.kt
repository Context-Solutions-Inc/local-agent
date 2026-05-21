package com.contextsolutions.mobileagent.search.vertical

import com.contextsolutions.mobileagent.preferences.GpsCoordinates
import com.contextsolutions.mobileagent.preferences.SourceKind
import com.contextsolutions.mobileagent.preferences.UserLocation
import com.contextsolutions.mobileagent.preferences.VerticalPreferences
import com.contextsolutions.mobileagent.search.SearchOutcome
import com.contextsolutions.mobileagent.search.SearchService
import com.contextsolutions.mobileagent.search.SearchSubtype

/**
 * Reuses [SearchService] (Brave) with a `site:` filter built from the user's
 * preferred domains for a [subtype]. Drives NEWS and SPORTS: Brave snippet
 * quality is good for both, and for sports a web search restricted to e.g.
 * `espn.com` answers historical/specific queries ("who won the masters last
 * year") that RSS headline feeds can't. The freshness/structured-data gap
 * that pushes weather/finance to direct fetches doesn't apply here.
 *
 * Construction: rewrites `query` into `"$query (site:d1 OR site:d2 OR ...)"`
 * using up to [maxDomains] domains from `prefs.sitesFor(subtype)` filtered to
 * [SourceKind.BRAVE_SITE_FILTER]. Any non-Brave entries in the list are
 * ignored (the user is free to mix kinds in a vertical's site list).
 */
class BraveSiteFilterAdapter(
    private val searchService: SearchService,
    private val maxDomains: Int = DEFAULT_MAX_DOMAINS,
    private val subtype: SearchSubtype = SearchSubtype.NEWS,
) : VerticalSearchAdapter {

    override suspend fun fetch(
        query: String,
        prefs: VerticalPreferences,
        location: UserLocation?,
        gps: GpsCoordinates?,
    ): SearchOutcome {
        val domains = prefs.sitesFor(subtype)
            .filter { it.kind == SourceKind.BRAVE_SITE_FILTER }
            .map { it.endpointTemplate.ifBlank { it.domain } }
            .distinct()
            .take(maxDomains)
        val effectiveQuery = if (domains.isEmpty()) {
            query
        } else {
            val filter = domains.joinToString(separator = " OR ") { "site:$it" }
            "$query ($filter)"
        }
        return searchService.search(effectiveQuery)
    }

    private companion object {
        const val DEFAULT_MAX_DOMAINS = 3
    }
}
