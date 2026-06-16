package com.contextsolutions.localagent.preferences

import com.contextsolutions.localagent.search.SearchSubtype
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSiteResolverTest {

    private val sampleJson = """
        {
          "fallback": "US",
          "countries": {
            "CA": {
              "weather": [
                { "domain": "weather.gc.ca", "displayName": "Env Canada", "kind": "RSS", "endpointTemplate": "https://weather.gc.ca/rss/city/{region}.xml" }
              ],
              "news": [
                { "domain": "cbc.ca", "displayName": "CBC", "kind": "BRAVE_SITE_FILTER", "endpointTemplate": "cbc.ca" }
              ]
            },
            "US": {
              "weather": [
                { "domain": "api.weather.gov", "displayName": "NWS", "kind": "JSON", "endpointTemplate": "https://api.weather.gov/points/{city}" }
              ]
            }
          }
        }
    """.trimIndent()

    private val resolver = DefaultSiteResolver(sampleJson)

    @Test
    fun returns_country_specific_defaults() {
        val ca = resolver.defaultsFor("CA")
        assertEquals(1, ca.weather.size)
        assertEquals("weather.gc.ca", ca.weather[0].domain)
        assertEquals(SourceKind.RSS, ca.weather[0].kind)
        assertEquals(1, ca.news.size)
        assertEquals("cbc.ca", ca.news[0].domain)
    }

    @Test
    fun falls_back_when_country_unknown() {
        val xx = resolver.defaultsFor("XX")
        assertEquals(1, xx.weather.size)
        assertEquals("api.weather.gov", xx.weather[0].domain)
    }

    @Test
    fun country_lookup_is_case_insensitive() {
        assertEquals(SearchSubtype.WEATHER.let { resolver.defaultsFor("ca").weather[0].domain }, "weather.gc.ca")
    }

    @Test
    fun merge_preserves_user_overrides_per_vertical() {
        val defaults = resolver.defaultsFor("CA")
        val user = VerticalPreferences(
            news = listOf(
                SiteConfig("globeandmail.com", "G&M", SourceKind.BRAVE_SITE_FILTER, "globeandmail.com"),
            ),
        )
        val merged = DefaultSiteResolver.merge(defaults = defaults, user = user)
        // News was overridden by user.
        assertEquals(1, merged.news.size)
        assertEquals("globeandmail.com", merged.news[0].domain)
        // Weather was untouched, so defaults win.
        assertEquals(1, merged.weather.size)
        assertEquals("weather.gc.ca", merged.weather[0].domain)
    }

    @Test
    fun apply_placeholders_substitutes_known_tokens() {
        val loc = UserLocation(country = "CA", regionCode = "ON", city = "Toronto")
        val url = DefaultSiteResolver.applyPlaceholders(
            template = "https://example.com/{country}/{region}/{city}?q={query}",
            location = loc,
            query = "current weather",
        )
        assertEquals("https://example.com/CA/ON/Toronto?q=current+weather", url)
    }

    @Test
    fun apply_placeholders_leaves_unknown_tokens_in_place() {
        val url = DefaultSiteResolver.applyPlaceholders(
            template = "https://example.com/{unknown}/path",
            location = null,
            query = "q",
        )
        assertNotNull(url)
        assertTrue("unknown token preserved", url!!.contains("{unknown}"))
    }

    @Test
    fun apply_placeholders_substitutes_lat_lon_when_gps_provided() {
        val gps = GpsCoordinates(latitude = 43.6532, longitude = -79.3832)
        val url = DefaultSiteResolver.applyPlaceholders(
            template = "https://weather.gc.ca/en/location/index.html?coords={lat},{lon}",
            location = null,
            query = "weather",
            gps = gps,
        )
        assertEquals(
            "https://weather.gc.ca/en/location/index.html?coords=43.6532,-79.3832",
            url,
        )
    }

    @Test
    fun apply_placeholders_returns_null_when_template_needs_gps_but_gps_is_null() {
        val url = DefaultSiteResolver.applyPlaceholders(
            template = "https://weather.gc.ca/en/location/index.html?coords={lat},{lon}",
            location = UserLocation("CA", "ON", "Toronto"),
            query = "weather",
            gps = null,
        )
        assertNull("template demanding {lat}/{lon} without gps must return null", url)
    }

    @Test
    fun apply_placeholders_ignores_gps_when_template_does_not_use_it() {
        val url = DefaultSiteResolver.applyPlaceholders(
            template = "https://example.com/{country}/{region}/path",
            location = UserLocation("CA", "ON", "Toronto"),
            query = "q",
            gps = GpsCoordinates(43.6532, -79.3832),
        )
        assertEquals("https://example.com/CA/ON/path", url)
    }
}
