package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.search.FormattedSearchPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Renders a [FormattedSearchPayload] from the WEATHER vertical into a
 * deterministic chat-friendly bubble — no LLM involved.
 *
 * Why: Gemma 4 E2B is unreliable at parsing Environment Canada's structured
 * RSS output (refuses with "I don't have real-time data" or mangles numbers
 * even when the [SEARCH CONTEXT] block carries clean data). Weather is
 * structured CRUD, exactly the surface where the deterministic short-circuit
 * pattern used for clock ([ClockResponseFormatter] /
 * [AgentLoop.runClockCommandDirect]) and todo ([TodoResponseFormatter])
 * shines. The agent loop calls [format] when classifier+regex pick the
 * weather subtype and the RSS fetch succeeds; on null return (unfamiliar
 * payload shape, empty entries) the loop falls through to the legacy LLM
 * path so non-CA weather queries keep working.
 *
 * Input shape — produced by [com.contextsolutions.localagent.search.vertical.FeedAdapter]:
 * ```
 * {
 *   "subtype": "weather",
 *   "query": "...",
 *   "sources": [
 *     {
 *       "domain": "weather.gc.ca",
 *       "url": "...",
 *       "snippet": "...",
 *       "payload": [
 *         {"title": "...", "link": "...", "description": "...", "published": "..."}
 *       ]
 *     }
 *   ]
 * }
 * ```
 *
 * Entry categorisation by title prefix (stable across EC's feed):
 *  - `Current Conditions:` → current
 *  - contains `WARNING` / `WATCH` / `ADVISORY` / `STATEMENT` → alert
 *  - else → forecast period
 */
object WeatherResponseFormatter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Render a weather payload. Returns the chat-bubble text on success,
     * `null` when the payload doesn't match the expected EC RSS shape so
     * the caller falls through to the LLM path.
     */
    fun format(city: String?, payload: FormattedSearchPayload): String? {
        val entries = parseEntries(payload.json) ?: return null
        if (entries.isEmpty()) return null

        val alerts = mutableListOf<Entry>()
        val current = mutableListOf<Entry>()
        val forecasts = mutableListOf<Entry>()
        for (e in entries) {
            when (categorize(e.title)) {
                Category.Alert -> alerts.add(e)
                Category.Current -> current.add(e)
                Category.Forecast -> forecasts.add(e)
            }
        }

        // Bare-minimum content check: need something current OR forecast.
        // An alerts-only payload alone isn't useful enough to bypass the
        // LLM — fall through so the model can synthesize an answer.
        if (current.isEmpty() && forecasts.isEmpty()) return null

        val locationLabel = city?.takeIf { it.isNotBlank() } ?: "your area"
        val source = payload.sources.firstOrNull()?.url
            ?.let { domainOf(it) }
            ?: "weather.gc.ca"
        val published = current.firstOrNull()?.published
            ?: forecasts.firstOrNull()?.published

        return buildString {
            append("Weather for ").append(locationLabel).append('\n')

            if (alerts.isNotEmpty()) {
                append('\n')
                append("⚠ ").append(shortAlertLine(alerts.first().title)).append('\n')
            }

            current.firstOrNull()?.let { cur ->
                append('\n')
                renderCurrent(cur).let { append(it) }
            }

            if (forecasts.isNotEmpty()) {
                append('\n').append('\n')
                forecasts.take(FORECAST_PERIODS).forEach { f ->
                    append("• ").append(f.title.trim()).append('\n')
                }
            }

            append('\n')
            append("Source: ").append(source)
            if (!published.isNullOrBlank()) {
                append(" · Updated ").append(formatTimestamp(published))
            }
        }
    }

    private fun renderCurrent(entry: Entry): String {
        val desc = entry.description
        val condition = CONDITION.find(desc)?.groupValues?.get(1)?.trim()
        val temp = TEMPERATURE.find(desc)?.groupValues?.get(1)
        val humidex = HUMIDEX.find(desc)?.groupValues?.get(1)
        val wind = WIND.find(desc)?.groupValues?.get(1)?.trim()
        val humidity = HUMIDITY.find(desc)?.groupValues?.get(1)

        // If none of the field regexes matched, fall back to the raw
        // description (truncated). EC may have shipped a slightly
        // different shape than we expect.
        if (condition == null && temp == null && wind == null) {
            val fallback = (entry.title.removePrefix("Current Conditions:").trim() +
                if (desc.isNotBlank()) ". " + desc.take(180) else "")
                .trim()
            if (fallback.isBlank()) return ""
            return "Now: $fallback\n"
        }

        return buildString {
            append("Now: ")
            if (condition != null) {
                append(condition)
                if (temp != null) append(", ").append(temp).append("°C")
                if (humidex != null) append(" (humidex ").append(humidex).append(")")
            } else if (temp != null) {
                append(temp).append("°C")
                if (humidex != null) append(" (humidex ").append(humidex).append(")")
            }
            append('\n')
            if (wind != null || humidity != null) {
                val parts = mutableListOf<String>()
                if (wind != null) parts.add("Wind $wind")
                if (humidity != null) parts.add("Humidity $humidity%")
                append(parts.joinToString(" · ")).append('\n')
            }
        }
    }

    private fun parseEntries(jsonText: String): List<Entry>? = try {
        val root = json.parseToJsonElement(jsonText).jsonObject
        val sources = root["sources"]?.jsonArray ?: return null
        val firstSource = sources.firstOrNull()?.jsonObject ?: return null
        val payloadArray = firstSource["payload"] as? JsonArray ?: return null
        payloadArray.map { node ->
            val obj = node.jsonObject
            Entry(
                title = obj["title"]?.jsonPrimitive?.content.orEmpty(),
                description = obj["description"]?.jsonPrimitive?.content.orEmpty(),
                published = obj["published"]?.jsonPrimitive?.content,
            )
        }
    } catch (_: Throwable) {
        null
    }

    private fun categorize(title: String): Category {
        val t = title.uppercase()
        return when {
            title.startsWith("Current Conditions") -> Category.Current
            "WARNING" in t || "WATCH" in t || "ADVISORY" in t || "STATEMENT" in t ->
                Category.Alert
            else -> Category.Forecast
        }
    }

    /**
     * Convert "YELLOW WARNING - HEAT, Toronto" → "Heat warning in effect".
     * Falls back to the original title when the shape doesn't match.
     */
    private fun shortAlertLine(title: String): String {
        val m = ALERT_PATTERN.find(title) ?: return title.trim()
        val kind = m.groupValues[2].trim().lowercase().replaceFirstChar { it.uppercase() }
        val level = m.groupValues[1].trim().lowercase()
        return when (level) {
            "warning" -> "$kind warning in effect"
            "watch" -> "$kind watch in effect"
            "advisory" -> "$kind advisory in effect"
            "statement" -> "$kind statement issued"
            else -> "$kind alert in effect"
        }
    }

    /**
     * Best-effort domain extraction from a URL. We avoid pulling in a URL
     * library — for our use the URLs are well-formed http(s) and we just
     * want the host for the citation line.
     */
    private fun domainOf(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        val host = afterScheme.substringBefore('/').substringBefore('?')
        return host.removePrefix("www.")
    }

    /**
     * EC publishes Atom `<published>` in ISO-8601 UTC ("2026-05-18T20:00:00Z").
     * We render that as "Mon May 18 8:00 PM UTC" — not strictly user-locale,
     * but close enough, locale-free, and unambiguous. Bigger formatting
     * polish can land later if users complain.
     */
    private fun formatTimestamp(raw: String): String {
        // Cheap path: keep the date+time, drop the "T" and the trailing "Z".
        // "2026-05-18T20:00:00Z" → "2026-05-18 20:00 UTC"
        val trimmed = raw.replace("T", " ").trimEnd('Z').take(16)
        return "$trimmed UTC"
    }

    private data class Entry(
        val title: String,
        val description: String,
        val published: String?,
    )

    private enum class Category { Alert, Current, Forecast }

    // Number of forecast periods we render. EC ships 12 periods (today +
    // 6 days at half-day granularity); 6 covers today + next 3 days,
    // which matches the chat-bubble bar of readability.
    private const val FORECAST_PERIODS = 6

    // EC's current-conditions description is a sequence of "Field: value"
    // pairs separated by whitespace (the original <br/> tags get stripped
    // by RssParser.cleanText). Each anchor is the field label; the value
    // captures up to the next field label or end-of-string.
    private val CONDITION = Regex("""Condition:\s*([^.\n]+?)(?:\s+Temperature:|\s+Pressure:|$)""")
    private val TEMPERATURE = Regex("""Temperature:\s*(-?\d+(?:\.\d+)?)°C""")
    private val HUMIDEX = Regex("""Humidex:\s*(\d+)""")
    private val HUMIDITY = Regex("""Humidity:\s*(\d+)\s*%""")
    private val WIND = Regex(
        """Wind:\s*([^\n]+?)(?:\s+Air Quality|\s+Humidity:|\s+Dewpoint:|\s+Pressure:|$)""",
    )

    // "YELLOW WARNING - HEAT" / "SPECIAL WEATHER STATEMENT" / etc.
    // Group 1: warning/watch/advisory/statement; Group 2: kind ("HEAT").
    private val ALERT_PATTERN = Regex(
        """\b(WARNING|WATCH|ADVISORY|STATEMENT)\b\s*-?\s*([A-Z][A-Z ]*?)\s*(?:,|$)""",
        RegexOption.IGNORE_CASE,
    )
}
