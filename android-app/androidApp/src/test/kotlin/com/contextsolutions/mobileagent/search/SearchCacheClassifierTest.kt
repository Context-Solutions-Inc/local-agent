package com.contextsolutions.mobileagent.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchCacheClassifierTest {

    @Test
    fun `time-sensitive when query mentions a recency token`() {
        listOf(
            "what's the weather",
            "weather in toronto",
            "AAPL price now",
            "score eagles cowboys",
            "eagles scores last night",
            "what's happening today",
            "current bitcoin price",
        ).forEach {
            assertEquals(it, SearchCacheCategory.TIME_SENSITIVE, SearchCacheClassifier.categorize(it))
        }
    }

    @Test
    fun `general when query has no recency tokens`() {
        listOf(
            "kotlin coroutines tutorial",
            "history of the eagles franchise",
            "explain transformers",
            "how to bake bread",
        ).forEach {
            assertEquals(it, SearchCacheCategory.GENERAL, SearchCacheClassifier.categorize(it))
        }
    }

    @Test
    fun `whole-word matching does not trigger on substring matches`() {
        // "today" appears inside "todayonline" — should NOT trip the time-sensitive band.
        assertEquals(SearchCacheCategory.GENERAL, SearchCacheClassifier.categorize("todayonline news"))
        // "score" inside "scoreboard" — same story.
        assertEquals(SearchCacheCategory.GENERAL, SearchCacheClassifier.categorize("scoreboard design"))
    }

    @Test
    fun `case-insensitive token matching`() {
        assertEquals(SearchCacheCategory.TIME_SENSITIVE, SearchCacheClassifier.categorize("WEATHER tomorrow"))
    }

    @Test
    fun `category exposes correct TTL and storage id`() {
        assertEquals(5 * 60 * 1_000L, SearchCacheCategory.TIME_SENSITIVE.ttlMs)
        assertEquals(24 * 60 * 60 * 1_000L, SearchCacheCategory.GENERAL.ttlMs)
        assertEquals("time_sensitive", SearchCacheCategory.TIME_SENSITIVE.storageId)
        assertEquals("general", SearchCacheCategory.GENERAL.storageId)
    }
}
