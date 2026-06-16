package com.contextsolutions.localagent.search.vertical

/**
 * Minimal parser for NWS Digital Weather Markup Language (DWML), the XML
 * served by `forecast.weather.gov/MapClick.php?...&FcstType=dwml`. Like
 * [RssParser] it is regex-based (no XML library) — DWML is shallow enough
 * that the four things we need (current conditions + worded forecast periods)
 * are cheap to pull without a full parse.
 *
 * Output is shaped as [RssEntry] list — the SAME shape [RssParser] produces —
 * so the WEATHER pipeline ([FeedAdapter] → `WeatherResponseFormatter`) renders
 * US weather deterministically (no LLM), exactly the way Environment Canada's
 * RSS is rendered for Canada:
 *
 *  - ONE current-conditions entry: `title = "Current Conditions: <summary>"`,
 *    `description = "<temp>°F. Humidity <h>%. Wind <…>"`. The formatter
 *    categorises it as "current" by the title prefix and renders it via its
 *    fallback path (its EC `°C`/label regexes don't match our `°F`,
 *    label-less text, which is intentional).
 *  - One forecast entry per worded period: `title = "<periodName>: <text>"`.
 *
 * DWML separates a parameter's *values* from their *timing*: a parameter
 * (`<wordedForecast>`, `<temperature>`, …) carries a `time-layout` key, and
 * its values align index-by-index to the `<start-valid-time>` entries of the
 * referenced `<time-layout>`. We index every time-layout first, then zip.
 */
class DwmlParser {

    fun parse(xml: String, max: Int = DEFAULT_MAX_ENTRIES): List<RssEntry> {
        val layouts = parseTimeLayouts(xml)
        val forecastBlock = DATA_FORECAST.find(xml)?.groupValues?.get(1)
        val currentBlock = DATA_CURRENT.find(xml)?.groupValues?.get(1)

        val entries = mutableListOf<RssEntry>()

        parseCurrent(currentBlock, fallbackTime = firstForecastTime(forecastBlock, layouts))
            ?.let { entries.add(it) }

        if (forecastBlock != null) {
            entries.addAll(parseForecast(forecastBlock, layouts))
        }

        return if (entries.size > max) entries.take(max) else entries
    }

    // --- time-layouts -------------------------------------------------------

    private data class Period(val name: String, val startTime: String?)

    private fun parseTimeLayouts(xml: String): Map<String, List<Period>> {
        val out = mutableMapOf<String, List<Period>>()
        for (m in TIME_LAYOUT.findAll(xml)) {
            val body = m.groupValues[1]
            val key = LAYOUT_KEY.find(body)?.groupValues?.get(1)?.trim() ?: continue
            val periods = START_VALID_TIME.findAll(body).map { sm ->
                Period(name = sm.groupValues[1].trim(), startTime = sm.groupValues[2].trim().ifBlank { null })
            }.toList()
            out[key] = periods
        }
        return out
    }

    private fun firstForecastTime(forecastBlock: String?, layouts: Map<String, List<Period>>): String? {
        forecastBlock ?: return null
        val key = WORDED_FORECAST.find(forecastBlock)?.groupValues?.get(1)?.trim() ?: return null
        return layouts[key]?.firstOrNull()?.startTime
    }

    // --- forecast -----------------------------------------------------------

    private fun parseForecast(block: String, layouts: Map<String, List<Period>>): List<RssEntry> {
        val worded = WORDED_FORECAST.find(block) ?: return emptyList()
        val layoutKey = worded.groupValues[1].trim()
        val periods = layouts[layoutKey].orEmpty()
        val texts = TEXT.findAll(worded.groupValues[2]).map { cleanText(it.groupValues[1]) }.toList()
        val n = minOf(texts.size, periods.size)
        if (n == 0) return emptyList()
        return (0 until n).mapNotNull { i ->
            val text = texts[i]
            if (text.isBlank()) return@mapNotNull null
            val name = periods[i].name.ifBlank { "Forecast" }
            RssEntry(
                title = "$name: $text",
                link = "",
                description = "",
                pubDate = periods[i].startTime,
            )
        }
    }

    // --- current observations ----------------------------------------------

    private fun parseCurrent(block: String?, fallbackTime: String?): RssEntry? {
        block ?: return null
        val summary = WEATHER_SUMMARY.find(block)?.groupValues?.get(1)?.let { cleanText(it) }?.ifBlank { null }
        val temp = firstValue(TEMP_APPARENT.find(block)?.groupValues?.get(1))
            ?: firstValue(ANY_TEMPERATURE.find(block)?.groupValues?.get(1))
        val humidity = firstValue(HUMIDITY.find(block)?.groupValues?.get(1))
        val wind = parseWind(block)

        // Need at least one observed field to render a current line.
        if (summary == null && temp == null && humidity == null && wind == null) return null

        val tempLabel = temp?.let { "$it°F" }
        // The title suffix must be non-empty so the formatter's fallback path
        // renders "Now: <suffix>. …" cleanly (no leading ". "). Prefer the
        // weather summary; else lead with temperature; else a generic word.
        val titleSuffix = summary ?: tempLabel ?: "Currently"
        val descParts = buildList {
            // If the summary occupies the title, temperature belongs in the
            // description; if temperature is the title suffix, don't repeat it.
            if (summary != null && tempLabel != null) add(tempLabel)
            if (humidity != null) add("Humidity $humidity%")
            if (wind != null) add("Wind $wind")
        }
        return RssEntry(
            title = "Current Conditions: $titleSuffix",
            link = "",
            description = descParts.joinToString(". "),
            pubDate = CURRENT_TIME.find(block)?.groupValues?.get(1)?.trim()?.ifBlank { null } ?: fallbackTime,
        )
    }

    private fun parseWind(block: String): String? {
        val speedEl = WIND_SPEED.find(block)?.groupValues?.get(0) ?: return null
        val speed = firstValue(WIND_SPEED.find(block)?.groupValues?.get(1)) ?: return null
        val units = UNITS_ATTR.find(speedEl)?.groupValues?.get(1)?.trim().orEmpty()
        val degrees = firstValue(WIND_DIR.find(block)?.groupValues?.get(1))?.toIntOrNull()
        val compass = degrees?.let { degreesToCompass(it) }
        return buildString {
            if (compass != null) append(compass).append(' ')
            append(speed)
            if (units.isNotBlank()) append(' ').append(units)
        }.ifBlank { null }
    }

    /** Pull the first `<value>` text out of a parameter element body. */
    private fun firstValue(elementBody: String?): String? {
        elementBody ?: return null
        val m = VALUE.find(elementBody) ?: return null
        val raw = m.groupValues[1].trim()
        // DWML marks missing data with xsi:nil; an empty <value/> matches with
        // a blank capture. Treat both as absent.
        if (raw.isBlank() || m.groupValues[0].contains("nil=\"true\"")) return null
        return raw
    }

    private fun degreesToCompass(deg: Int): String {
        val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val idx = (((deg % 360) + 360) % 360 + 22) / 45 % 8
        return dirs[idx]
    }

    /** Strip inline tags + decode the common entities (mirrors [RssParser]). */
    private fun cleanText(raw: String): String =
        raw.replace(TAG, " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace("&deg;", "°")
            .replace("&#176;", "°")
            .replace(WHITESPACE_RUN, " ")
            .trim()

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 8

        val TAG = Regex("""<[^>]+>""")
        val WHITESPACE_RUN = Regex("""\s+""")

        private val DOTALL_CI = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)

        val DATA_FORECAST = Regex("""<data\b[^>]*type=["']forecast["'][^>]*>(.*?)</data>""", DOTALL_CI)
        val DATA_CURRENT = Regex("""<data\b[^>]*type=["']current observations["'][^>]*>(.*?)</data>""", DOTALL_CI)

        val TIME_LAYOUT = Regex("""<time-layout\b[^>]*>(.*?)</time-layout>""", DOTALL_CI)
        val LAYOUT_KEY = Regex("""<layout-key\b[^>]*>(.*?)</layout-key>""", DOTALL_CI)
        val START_VALID_TIME =
            Regex("""<start-valid-time\b[^>]*\bperiod-name=["']([^"']*)["'][^>]*>(.*?)</start-valid-time>""", DOTALL_CI)

        val WORDED_FORECAST =
            Regex("""<wordedForecast\b[^>]*\btime-layout=["']([^"']*)["'][^>]*>(.*?)</wordedForecast>""", DOTALL_CI)
        val TEXT = Regex("""<text\b[^>]*>(.*?)</text>""", DOTALL_CI)

        val WEATHER_SUMMARY = Regex("""weather-summary=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        val TEMP_APPARENT = Regex("""<temperature\b[^>]*type=["']apparent["'][^>]*>(.*?)</temperature>""", DOTALL_CI)
        val ANY_TEMPERATURE = Regex("""<temperature\b[^>]*>(.*?)</temperature>""", DOTALL_CI)
        val HUMIDITY = Regex("""<humidity\b[^>]*>(.*?)</humidity>""", DOTALL_CI)
        val WIND_SPEED = Regex("""<wind-speed\b[^>]*type=["']sustained["'][^>]*>(.*?)</wind-speed>""", DOTALL_CI)
        val WIND_DIR = Regex("""<direction\b[^>]*type=["']wind["'][^>]*>(.*?)</direction>""", DOTALL_CI)
        val UNITS_ATTR = Regex("""units=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        val VALUE = Regex("""<value\b[^>]*>(.*?)</value>""", DOTALL_CI)
        // First time-stamp in the current-observations block (its time-layout
        // has no period-name, so START_VALID_TIME won't catch it).
        val CURRENT_TIME = Regex("""<start-valid-time\b[^>]*>(.*?)</start-valid-time>""", DOTALL_CI)
    }
}
