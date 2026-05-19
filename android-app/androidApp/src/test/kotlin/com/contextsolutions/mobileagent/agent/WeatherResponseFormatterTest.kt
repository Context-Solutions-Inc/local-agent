package com.contextsolutions.mobileagent.agent

import com.contextsolutions.mobileagent.search.FormattedSearchPayload
import com.contextsolutions.mobileagent.search.SearchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherResponseFormatterTest {

    /**
     * The JSON shape FeedAdapter emits for an EC RSS-by-coords fetch.
     * Trimmed to the parts the formatter reads. Mirrors the actual
     * payload structure for Toronto on 2026-05-18 (heat warning, current
     * conditions at Pearson Int'l, 12 forecast periods).
     */
    private val torontoJson = """
        {
          "subtype": "weather",
          "query": "weather Toronto",
          "sources": [
            {
              "domain": "weather.gc.ca",
              "display_name": "Environment Canada",
              "url": "https://weather.gc.ca/rss/weather/43.6532_-79.3832_e.xml",
              "snippet": "...",
              "payload": [
                {
                  "title": "YELLOW WARNING - HEAT, Toronto",
                  "link": "https://weather.gc.ca/warnings/report_e.html?on61",
                  "description": "Persons in or near this area should be on the lookout for adverse weather conditions. Issued: 3:58 PM EDT Monday 18 May 2026",
                  "published": "2026-05-18T19:58:00Z"
                },
                {
                  "title": "Current Conditions: Mostly Cloudy, 29.7°C",
                  "link": "https://weather.gc.ca/en/location/index.html?coords=43.655,-79.383",
                  "description": "Observed at: Toronto Pearson Int'l Airport 7:00 PM EDT Monday 18 May 2026 Condition: Mostly Cloudy Temperature: 29.7°C Pressure / Tendency: 101.3 kPa falling Visibility: 24 km Humidity: 41 % Humidex: 34 Dewpoint: 14.9°C Wind: SW 26 km/h gust 48 km/h Air Quality Health Index: 4",
                  "published": "2026-05-18T20:00:00Z"
                },
                {
                  "title": "Monday night: Chance of showers. Low 18. POP 40%",
                  "link": "...",
                  "description": "Clear. Increasing cloudiness this evening then 40 percent chance of showers late this evening and overnight.",
                  "published": "2026-05-18T19:30:00Z"
                },
                {
                  "title": "Tuesday: Chance of showers. High 29. POP 30%",
                  "link": "...",
                  "description": "Mainly cloudy. 30 percent chance of showers late in the morning and in the afternoon.",
                  "published": "2026-05-18T19:30:00Z"
                },
                {
                  "title": "Tuesday night: Chance of showers. Low 10. POP 60%",
                  "link": "...",
                  "description": "Mainly cloudy with 60 percent chance of showers.",
                  "published": "2026-05-18T19:30:00Z"
                },
                {
                  "title": "Wednesday: Chance of showers. High 17. POP 30%",
                  "link": "...",
                  "description": "A mix of sun and cloud with 30 percent chance of showers.",
                  "published": "2026-05-18T19:30:00Z"
                },
                {
                  "title": "Wednesday night: Clear. Low 6.",
                  "link": "...",
                  "description": "Clear. Low 6.",
                  "published": "2026-05-18T19:30:00Z"
                },
                {
                  "title": "Thursday: Sunny. High 16.",
                  "link": "...",
                  "description": "Sunny. High 16.",
                  "published": "2026-05-18T19:30:00Z"
                }
              ]
            }
          ]
        }
    """.trimIndent()

    private fun payload(json: String): FormattedSearchPayload = FormattedSearchPayload(
        json = json,
        sources = listOf(
            SearchSource(
                title = "Environment Canada",
                url = "https://weather.gc.ca/rss/weather/43.6532_-79.3832_e.xml",
                snippet = "",
            ),
        ),
    )

    @Test
    fun renders_full_bubble_for_toronto_fixture() {
        val out = WeatherResponseFormatter.format(city = "Toronto", payload = payload(torontoJson))
        assertNotNull(out)
        val text = out!!
        // Header
        assertTrue("location header missing", text.contains("Weather for Toronto"))
        // Alert
        assertTrue("alert banner missing", text.contains("⚠"))
        assertTrue("alert kind missing", text.contains("Heat warning"))
        // Current conditions parsed from description fields
        assertTrue("current conditions header missing", text.contains("Now:"))
        assertTrue("condition phrase missing", text.contains("Mostly Cloudy"))
        assertTrue("temperature missing", text.contains("29.7°C"))
        assertTrue("humidex missing", text.contains("humidex 34"))
        assertTrue("wind missing", text.contains("Wind SW 26 km/h gust 48 km/h"))
        assertTrue("humidity missing", text.contains("Humidity 41%"))
        // Forecast bullets
        assertTrue("Tonight bullet missing", text.contains("• Monday night"))
        assertTrue("Tuesday bullet missing", text.contains("• Tuesday:"))
        assertTrue("Thursday bullet missing", text.contains("• Thursday"))
        // Source line
        assertTrue("source line missing", text.contains("Source: weather.gc.ca"))
        assertTrue("updated timestamp missing", text.contains("Updated"))
        // Length sanity — fits a chat bubble
        assertTrue("output too long: ${text.length}", text.length < 1000)
    }

    @Test
    fun forecast_list_is_capped_to_six_periods() {
        val out = WeatherResponseFormatter.format(city = "Toronto", payload = payload(torontoJson))!!
        val bullets = out.lines().count { it.startsWith("• ") }
        assertEquals("forecast bullets should cap at 6", 6, bullets)
    }

    @Test
    fun returns_null_when_sources_array_missing() {
        val out = WeatherResponseFormatter.format(
            city = "Toronto",
            payload = payload("""{"subtype":"weather","query":"x"}"""),
        )
        assertNull(out)
    }

    @Test
    fun returns_null_when_sources_empty() {
        val out = WeatherResponseFormatter.format(
            city = "Toronto",
            payload = payload("""{"sources":[]}"""),
        )
        assertNull(out)
    }

    @Test
    fun returns_null_when_payload_array_empty() {
        val out = WeatherResponseFormatter.format(
            city = "Toronto",
            payload = payload("""{"sources":[{"domain":"weather.gc.ca","payload":[]}]}"""),
        )
        assertNull(out)
    }

    @Test
    fun returns_null_when_only_alerts_present() {
        // Alerts alone aren't enough to bypass the LLM — fall through.
        val alertsOnly = """
            {"sources":[{"domain":"weather.gc.ca","payload":[
              {"title":"YELLOW WARNING - HEAT, Toronto","description":"x","published":"2026-05-18T19:58:00Z"}
            ]}]}
        """.trimIndent()
        assertNull(WeatherResponseFormatter.format(city = "Toronto", payload = payload(alertsOnly)))
    }

    @Test
    fun renders_without_alert_section_when_none_present() {
        val noAlert = """
            {"sources":[{"domain":"weather.gc.ca","payload":[
              {"title":"Current Conditions: Sunny, 22°C","description":"Condition: Sunny Temperature: 22°C Wind: N 10 km/h Humidity: 50 %","published":"2026-05-18T20:00:00Z"},
              {"title":"Tonight: Clear. Low 12.","description":"Clear.","published":"2026-05-18T19:30:00Z"}
            ]}]}
        """.trimIndent()
        val out = WeatherResponseFormatter.format(city = "Ottawa", payload = payload(noAlert))!!
        assertFalse("alert banner should not appear", out.contains("⚠"))
        assertTrue(out.contains("Now: Sunny, 22°C"))
        assertTrue(out.contains("• Tonight"))
    }

    @Test
    fun falls_back_to_your_area_when_city_is_null() {
        val out = WeatherResponseFormatter.format(city = null, payload = payload(torontoJson))!!
        assertTrue(out.contains("Weather for your area"))
    }

    @Test
    fun falls_back_to_raw_description_when_current_regexes_dont_match() {
        // EC field labels gone or reshaped — formatter should still render
        // something useful instead of dropping the current section entirely.
        val odd = """
            {"sources":[{"domain":"weather.gc.ca","payload":[
              {"title":"Current Conditions: weird","description":"some unstructured prose about today","published":"2026-05-18T20:00:00Z"},
              {"title":"Tonight: Clear","description":"Clear.","published":"2026-05-18T19:30:00Z"}
            ]}]}
        """.trimIndent()
        val out = WeatherResponseFormatter.format(city = "Toronto", payload = payload(odd))!!
        assertTrue("now line should appear", out.contains("Now:"))
        assertTrue("fallback prose should appear", out.contains("weird"))
    }
}
