package com.contextsolutions.mobileagent.search.vertical

import com.contextsolutions.mobileagent.preferences.GpsCoordinates
import com.contextsolutions.mobileagent.preferences.UserLocation
import com.contextsolutions.mobileagent.preferences.VerticalPreferences
import com.contextsolutions.mobileagent.search.SearchOutcome
import com.contextsolutions.mobileagent.search.SearchService

/**
 * GENERAL search adapter — pass-through to the existing
 * [SearchService] (Brave web search, 5 results, 4 KB budget). Preserves
 * pre-PR-#23 behaviour byte-for-byte.
 */
class GeneralSearchAdapter(
    private val searchService: SearchService,
) : VerticalSearchAdapter {
    override suspend fun fetch(
        query: String,
        prefs: VerticalPreferences,
        location: UserLocation?,
        gps: GpsCoordinates?,
    ): SearchOutcome = searchService.search(query)
}
