package com.contextsolutions.localagent.preferences

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * L6 (PR #6) — `applyPlaceholders` must URL-encode user-derived substitutions so
 * `&`/`?`/`#`/space can't inject into the feed query string.
 */
class DefaultSiteResolverEncodingTest {

    private val location = UserLocation(country = "CA", regionCode = "ON", city = "London")

    @Test
    fun query_special_chars_are_percent_encoded() {
        val out = DefaultSiteResolver.applyPlaceholders(
            template = "https://example.com/search?q={query}",
            location = location,
            query = "weather & news?x#y",
        )
        // No raw query-string metacharacters from the user input survive.
        assertTrue(out != null && out.startsWith("https://example.com/search?q="))
        val encoded = out!!.substringAfter("q=")
        assertTrue("&" !in encoded, "raw & must be encoded: $encoded")
        assertTrue("?" !in encoded, "raw ? must be encoded: $encoded")
        assertTrue("#" !in encoded, "raw # must be encoded: $encoded")
        assertTrue("%26" in encoded && "%23" in encoded, "expected percent-encoding: $encoded")
    }

    @Test
    fun spaces_become_plus_preserving_prior_behavior() {
        val out = DefaultSiteResolver.applyPlaceholders(
            template = "https://example.com/search?q={query}",
            location = location,
            query = "toronto weather",
        )
        assertEquals("https://example.com/search?q=toronto+weather", out)
    }

    @Test
    fun city_with_special_chars_is_encoded() {
        val out = DefaultSiteResolver.applyPlaceholders(
            template = "https://example.com/{city}",
            location = UserLocation(country = "CA", regionCode = "QC", city = "Québec City"),
            query = "x",
        )
        // The accented + spaced city must not appear raw.
        assertTrue(out != null && "Québec City" !in out)
        assertTrue("Qu%C3%A9bec" in out!!, "expected UTF-8 percent-encoding: $out")
    }
}
