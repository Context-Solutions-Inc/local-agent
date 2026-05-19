package com.contextsolutions.mobileagent.search.vertical

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for [toHumanReadableUrl]: the rule that maps machine-readable
 * fetch URLs onto consumer-facing pages, so a tap on a citation chip
 * lands the user on something they can read instead of raw XML.
 */
class CitationUrlRewriteTest {

    @Test
    fun rewrites_ec_rss_english_feed_to_html_location_page() {
        val out = toHumanReadableUrl(
            "https://weather.gc.ca/rss/weather/43.6532_-79.3832_e.xml",
        )
        assertEquals(
            "https://weather.gc.ca/en/location/index.html?coords=43.6532,-79.3832",
            out,
        )
    }

    @Test
    fun rewrites_ec_rss_french_feed_to_english_html_page() {
        // Even when fetching the French feed we point the user at the
        // English HTML page — the app is currently English-only. When
        // locale-aware citations land, switch on the suffix.
        val out = toHumanReadableUrl(
            "https://weather.gc.ca/rss/weather/45.5017_-73.5673_f.xml",
        )
        assertEquals(
            "https://weather.gc.ca/en/location/index.html?coords=45.5017,-73.5673",
            out,
        )
    }

    @Test
    fun handles_negative_lat_lon_pairs() {
        val out = toHumanReadableUrl(
            "https://weather.gc.ca/rss/weather/-33.8688_151.2093_e.xml",
        )
        assertEquals(
            "https://weather.gc.ca/en/location/index.html?coords=-33.8688,151.2093",
            out,
        )
    }

    @Test
    fun passes_through_unrelated_urls() {
        val brave = "https://api.search.brave.com/res/v1/web/search?q=eagles"
        assertEquals(brave, toHumanReadableUrl(brave))
        val twn = "https://www.theweathernetwork.com/en/city/ca/ON/Toronto/current"
        assertEquals(twn, toHumanReadableUrl(twn))
    }

    @Test
    fun passes_through_other_ec_paths_unchanged() {
        // Only the /rss/weather/{lat}_{lon}_{e|f}.xml shape rewrites.
        // Alert feeds (/rss/battleboard/) and any other paths keep their
        // fetch URL — the user CAN read those XML pages but they're
        // alert feeds, not forecasts.
        val alerts = "https://weather.gc.ca/rss/battleboard/on61_e.xml"
        assertEquals(alerts, toHumanReadableUrl(alerts))
    }
}
