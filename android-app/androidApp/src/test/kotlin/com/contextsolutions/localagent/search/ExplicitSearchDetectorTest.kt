package com.contextsolutions.localagent.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicitSearchDetectorTest {

    private val detector = ExplicitSearchDetector()

    private fun assertMatches(vararg queries: String) {
        for (q in queries) assertTrue("expected match: \"$q\"", detector.matches(q))
    }

    private fun assertNoMatch(vararg queries: String) {
        for (q in queries) assertFalse("expected NO match: \"$q\"", detector.matches(q))
    }

    @Test
    fun leading_web_commands_match() = assertMatches(
        "web search the URL of the Android Open Source Project",
        "websearch the eagles score",
        "search the web for the best pizza in toronto",
        "search the web who won the masters",
        "search online for flight prices",
        "search online the capital of peru",
        "web search: nvidia stock price",
    )

    @Test
    fun case_insensitive_and_leading_whitespace() = assertMatches(
        "WEB SEARCH the url of aosp",
        "  web search the url of aosp",
        "Search The Web For something",
    )

    @Test
    fun command_not_at_start_does_not_match() = assertNoMatch(
        "how do web search engines work",
        "i did a web search yesterday",
        "explain web search ranking",
        "tell me about searching the web",
    )

    @Test
    fun non_search_queries_do_not_match() = assertNoMatch(
        "what's the weather",
        "who won the super bowl last year",
        "what is photosynthesis",
        "google's quarterly revenue", // deliberately NOT a trigger (no "google" arm)
    )

    @Test
    fun strip_removes_command_and_connectives() {
        assertEquals(
            "the URL of the Android Open Source Project",
            detector.stripPrefix("web search the URL of the Android Open Source Project"),
        )
        assertEquals(
            "the best pizza in toronto",
            detector.stripPrefix("search the web for the best pizza in toronto"),
        )
        assertEquals("nvidia stock price", detector.stripPrefix("web search: nvidia stock price"))
        assertEquals("flight prices", detector.stripPrefix("search online for flight prices"))
    }

    @Test
    fun strip_leaves_original_when_no_match() {
        assertEquals("how do web search engines work", detector.stripPrefix("how do web search engines work"))
    }

    @Test
    fun strip_returns_original_when_remainder_empty() {
        // "web search" with no query behind it — fire the verbatim text rather
        // than an empty search.
        assertEquals("web search ", detector.stripPrefix("web search "))
    }
}
