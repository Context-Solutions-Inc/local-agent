package com.contextsolutions.localagent.search.vertical

import com.contextsolutions.localagent.preferences.GpsCoordinates
import com.contextsolutions.localagent.preferences.UserLocation
import com.contextsolutions.localagent.preferences.VerticalPreferences
import com.contextsolutions.localagent.search.SearchOutcome
import com.contextsolutions.localagent.search.SearchService

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
