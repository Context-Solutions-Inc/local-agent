package com.contextsolutions.localagent.search.vertical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [DwmlParser]: parsing NWS DWML XML into the [RssEntry] shape the
 * weather pipeline renders. The highest-value case is value↔period alignment —
 * DWML carries values positionally against a referenced `time-layout`, and the
 * parser must zip against the RIGHT layout when several are present.
 */
class DwmlParserTest {

    private val parser = DwmlParser()

    /** Miami-shaped DWML: one decoy time-layout + the real forecast layout,
     * a 3-period worded forecast, and a current-observations block. */
    private val miamiDwml = """
        <?xml version="1.0" encoding="ISO-8859-1"?>
        <dwml version="1.0">
          <head><product/></head>
          <data type="forecast">
            <location>
              <location-key>point1</location-key>
              <description>Miami, FL</description>
              <point latitude="25.78" longitude="-80.18"/>
            </location>
            <time-layout time-coordinate="local" summarization="12hourly">
              <layout-key>k-p12h-n13-1</layout-key>
              <start-valid-time period-name="DECOY_A">2026-05-21T06:00:00-04:00</start-valid-time>
              <start-valid-time period-name="DECOY_B">2026-05-21T18:00:00-04:00</start-valid-time>
            </time-layout>
            <time-layout time-coordinate="local" summarization="none">
              <layout-key>k-p24h-n7-1</layout-key>
              <start-valid-time period-name="Today">2026-05-21T08:00:00-04:00</start-valid-time>
              <start-valid-time period-name="Tonight">2026-05-21T20:00:00-04:00</start-valid-time>
              <start-valid-time period-name="Thursday">2026-05-22T08:00:00-04:00</start-valid-time>
            </time-layout>
            <parameters applicable-location="point1">
              <temperature type="maximum" units="Fahrenheit" time-layout="k-p24h-n7-1">
                <name>Daily Maximum Temperature</name>
                <value>85</value>
              </temperature>
              <wordedForecast time-layout="k-p24h-n7-1">
                <name>Text Forecast</name>
                <text>Sunny, with a high near 85. East wind 11 to 13 mph.</text>
                <text>Mostly clear, with a low around 76.</text>
                <text>Mostly sunny, with a high near 86.</text>
              </wordedForecast>
            </parameters>
          </data>
          <data type="current observations">
            <location>
              <location-key>point1</location-key>
              <description>Miami, FL</description>
            </location>
            <time-layout time-coordinate="local">
              <layout-key>k-p1h-n1-1</layout-key>
              <start-valid-time>2026-05-21T07:53:00-04:00</start-valid-time>
            </time-layout>
            <parameters applicable-location="point1">
              <temperature type="apparent" units="Fahrenheit" time-layout="k-p1h-n1-1">
                <value>81</value>
              </temperature>
              <humidity type="relative" time-layout="k-p1h-n1-1">
                <value>74</value>
              </humidity>
              <wind-speed type="sustained" units="knots" time-layout="k-p1h-n1-1">
                <value>9</value>
              </wind-speed>
              <direction type="wind" units="degrees true" time-layout="k-p1h-n1-1">
                <value>90</value>
              </direction>
              <weather time-layout="k-p1h-n1-1">
                <weather-conditions weather-summary="Mostly Sunny"/>
              </weather>
            </parameters>
          </data>
        </dwml>
    """.trimIndent()

    @Test
    fun parses_current_then_forecast_entries() {
        val entries = parser.parse(miamiDwml)
        // 1 current + 3 forecast periods
        assertEquals(4, entries.size)

        val current = entries.first()
        assertEquals("Current Conditions: Mostly Sunny", current.title)
        assertTrue("temperature missing", current.description.contains("81°F"))
        assertTrue("humidity missing", current.description.contains("Humidity 74%"))
        // 90° true → compass East; knots units carried through verbatim.
        assertTrue("wind missing", current.description.contains("Wind E 9 knots"))
        assertEquals("2026-05-21T07:53:00-04:00", current.pubDate)
    }

    @Test
    fun forecast_text_aligns_with_correct_time_layout_not_a_decoy() {
        val entries = parser.parse(miamiDwml)
        val forecasts = entries.drop(1)
        assertEquals(3, forecasts.size)
        // Period names come from the referenced k-p24h layout, NOT the decoy
        // k-p12h layout that appears first in the document.
        assertEquals("Today: Sunny, with a high near 85. East wind 11 to 13 mph.", forecasts[0].title)
        assertEquals("Tonight: Mostly clear, with a low around 76.", forecasts[1].title)
        assertEquals("Thursday: Mostly sunny, with a high near 86.", forecasts[2].title)
        assertFalse("decoy layout leaked into a forecast title", forecasts.any { it.title.contains("DECOY") })
        assertEquals("2026-05-21T08:00:00-04:00", forecasts[0].pubDate)
    }

    @Test
    fun sparse_current_observations_omit_missing_fields_without_stray_separators() {
        val sparse = """
            <dwml><data type="current observations">
              <parameters>
                <temperature type="apparent" units="Fahrenheit"><value>70</value></temperature>
                <weather><weather-conditions weather-summary="Fair"/></weather>
              </parameters>
            </data></dwml>
        """.trimIndent()
        val entries = parser.parse(sparse)
        val current = entries.first()
        assertEquals("Current Conditions: Fair", current.title)
        assertEquals("70°F", current.description)
        assertFalse("no humidity fragment", current.description.contains("Humidity"))
        assertFalse("no wind fragment", current.description.contains("Wind"))
        assertFalse("no trailing separator", current.description.endsWith("."))
    }

    @Test
    fun missing_weather_summary_leads_with_temperature_in_title() {
        val noSummary = """
            <dwml><data type="current observations">
              <parameters>
                <temperature type="apparent" units="Fahrenheit"><value>68</value></temperature>
                <humidity type="relative"><value>55</value></humidity>
              </parameters>
            </data></dwml>
        """.trimIndent()
        val current = parser.parse(noSummary).first()
        // No summary → temperature becomes the title suffix, so the formatter's
        // "Now: <suffix>. …" never renders an empty "Now: . " prefix.
        assertEquals("Current Conditions: 68°F", current.title)
        assertEquals("Humidity 55%", current.description)
    }

    @Test
    fun caps_total_entries_at_max() {
        val entries = parser.parse(miamiDwml, max = 2)
        assertEquals(2, entries.size)
    }

    @Test
    fun returns_empty_for_malformed_xml() {
        assertTrue(parser.parse("not xml at all").isEmpty())
        assertTrue(parser.parse("<html><body>error page</body></html>").isEmpty())
        assertTrue(parser.parse("").isEmpty())
    }
}
