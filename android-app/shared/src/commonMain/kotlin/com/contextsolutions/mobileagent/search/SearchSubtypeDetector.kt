package com.contextsolutions.mobileagent.search

/**
 * Regex/keyword refinement that picks a [SearchSubtype] AFTER the pre-flight
 * classifier has already decided search is needed. Mirrors the shape of
 * [com.contextsolutions.mobileagent.agent.ClockIntentDetector] — narrow,
 * inclusive, easy to inspect.
 *
 * Order matters: WEATHER → SPORTS → FINANCE → NEWS → GENERAL fallback.
 * The order minimises cross-vertical bleed for queries like "stock market news"
 * (FINANCE wins because the ticker/market vocabulary is more specific than the
 * generic "news" keyword). FINANCE covers both market-news and single-instrument
 * quotes ("Nvidia stock price", "$NVDA") — the old STOCKS subtype was merged in
 * (PR #35) once both routed to the same Brave `site:` path.
 *
 * Misroutes are not catastrophic — the adapter still returns citations the
 * user can fall back to, and the LLM synthesises whatever was fetched. The
 * worst case is "user asked about weather, got news about weather" which is
 * a degraded but recoverable experience.
 */
class SearchSubtypeDetector {

    fun detect(query: String): SearchSubtype {
        val lower = query.lowercase()
        return when {
            WEATHER_PATTERN.containsMatchIn(lower) -> SearchSubtype.WEATHER
            SPORTS_PATTERN.containsMatchIn(lower) -> SearchSubtype.SPORTS
            FINANCE_PATTERN.containsMatchIn(lower) -> SearchSubtype.FINANCE
            NEWS_PATTERN.containsMatchIn(lower) -> SearchSubtype.NEWS
            else -> SearchSubtype.GENERAL
        }
    }

    companion object {
        /**
         * True when [query] contains explicit weather vocabulary
         * (weather/forecast/temperature/raining/…). Reuses [WEATHER_PATTERN] so
         * the lexicon has a single source of truth. Used by the WEATHER
         * force-fire gate in
         * [com.contextsolutions.mobileagent.agent.AgentLoop]: a bare city
         * mention ("what is the history of london") must NOT hijack the forecast
         * path, so the catalog-city branch requires a weather word here.
         */
        fun mentionsWeather(query: String): Boolean =
            WEATHER_PATTERN.containsMatchIn(query.lowercase())

        // WEATHER — core conditions vocab + "feels like" + "raining/snowing"
        // verbs. "Cold" alone is too generic so it isn't here.
        private val WEATHER_PATTERN: Regex = Regex(
            """\b(weather|forecast|temperature|temp|humidity|precipitation|raining|snowing|storm|hurricane|tornado|wind\s*chill|uv\s*index|feels\s*like|pollen|aqi|air\s*quality)\b""",
        )

        // SPORTS — league names, action verbs, scoring/standings vocab.
        // League names cover the major North American + soccer leagues; add
        // more from telemetry if misroutes surface. Team names are NOT here
        // (too many; classifier-based subtyping is the right v1.x fix).
        private val SPORTS_PATTERN: Regex = Regex(
            """\b(score|scores|scoreboard|standings|playoff|playoffs|fixture|fixtures|kickoff|tipoff|matchday|wins|losses|game\s+(tonight|today|tomorrow|yesterday)|who\s+won|nhl|nba|nfl|mlb|nlb|mls|epl|premier\s*league|champions\s*league|world\s*cup|stanley\s*cup|super\s*bowl|olympics?)\b""",
        )

        // FINANCE — explicit market/stock vocab, covering both market news and
        // single-instrument quotes ("nvidia stock price", "price of apple
        // shares", "$NVDA"). `shares`/`price target` were folded in from the old
        // STOCKS pattern (PR #35). Avoids matching bare ticker symbols (too many
        // 3-letter false positives like "ATM", "CEO"); requires either a finance
        // keyword or a `$TICKER` prefix.
        private val FINANCE_PATTERN: Regex = Regex(
            """\b(stock|stocks|shares|ticker|share\s+price|price\s+target|market\s+cap|earnings|dividend|p/?e\s*ratio|ipo|nasdaq|nyse|tsx|s&p|dow\s*jones|crypto|bitcoin|ethereum|forex|exchange\s*rate|interest\s*rate|inflation|gdp|recession)\b|\$[a-zA-Z]{1,5}\b""",
        )

        // NEWS — explicit news-shaped vocabulary. Catches "latest" + topical
        // intent. Generic search wins ties where the query is a bare entity.
        private val NEWS_PATTERN: Regex = Regex(
            """\b(news|headlines|breaking|latest\s+on|latest\s+about|latest\s+news|happened|update\s+on|trending)\b""",
        )
    }
}
