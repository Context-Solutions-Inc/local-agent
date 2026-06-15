package com.contextsolutions.mobileagent.search

/**
 * Detects RELATIVE temporal references ("last year", "yesterday", "last week",
 * "tomorrow", …) in a query. Topic-agnostic — used by
 * [com.contextsolutions.mobileagent.classifier.PreflightRouter] to force the
 * FireSearch path regardless of the classifier's `p_search_required`.
 *
 * Why: the on-device LLM has a fixed knowledge cutoff, so any now-relative
 * question has a clock mismatch the model can't resolve. The shipped classifier
 * under-fires on these ("who won the super bowl last year" scored 0.175 →
 * middle band → stale Gemma answer). Matching the relative phrase up front and
 * forcing search is the precise fix; lowering the global threshold would hurt
 * precision everywhere. See CLAUDE.md invariant #38 and the WEATHER force-fire
 * precedent (#32).
 *
 * Mirrors [SearchSubtypeDetector]'s shape: lowercase the input, one
 * word-boundary regex, narrow and easy to inspect.
 *
 * **Excludes absolute/numeric references** ("in 2019", "Jan 5 2020") — the LLM
 * can answer fixed-point history from training; only the now-relative anchor is
 * unresolvable. The only numeric arm requires a trailing unit + "ago".
 *
 * **Accepted false positives** (per product directive): "how are you today",
 * "I had fun last year, write a poem" force-fire search. Harmless (a Brave
 * round-trip + GENERAL subtype); tracked via
 * [com.contextsolutions.mobileagent.telemetry.CounterNames.PREFLIGHT_TEMPORAL_FORCE_TOTAL]
 * so the band can be tuned from telemetry if it ever dominates.
 */
class RelativeTemporalDetector {

    fun matches(query: String): Boolean =
        RELATIVE_TEMPORAL_PATTERN.containsMatchIn(query.lowercase())

    /**
     * True when [query] carries a relative reference to the PAST ("yesterday",
     * "last year", "3 days ago"). Used by the WEATHER force-fire gate in
     * [com.contextsolutions.mobileagent.agent.AgentLoop] so a historical weather
     * question ("what was the weather like in London last year") falls through
     * to the LLM instead of rendering the live forecast. Subset of [matches];
     * the present/future + recency arms are excluded.
     */
    fun matchesPast(query: String): Boolean =
        PAST_PATTERN.containsMatchIn(query.lowercase())

    private companion object {
        // Bare "now" is intentionally NOT an alternative (too many false hits:
        // "now that…", "know how"); covered via "right now"/"currently"/"at the
        // moment". No 4-digit-year or month-name arm, so absolute dates never
        // match.
        private const val PAST =
            "yesterday|last\\s+night|the\\s+other\\s+day|recently|lately|" +
                "last\\s+(hour|week|weekend|month|year|night)|" +
                "\\d+\\s+(second|minute|hour|day|week|month|year)s?\\s+ago|" +
                "an?\\s+(hour|day|week|month|year)\\s+ago"

        private const val PRESENT_FUTURE =
            // PRESENT / NOW
            "today|tonight|currently|right\\s+now|at\\s+the\\s+moment|these\\s+days|" +
                "this\\s+(morning|afternoon|evening|week|weekend|month|year)|" +
                // RECENCY PHRASES — current-events idioms the v1.0 classifier
                // under-fires on ("what is the latest news"). Phrase-anchored to
                // a news/headlines noun so a bare recency adjective on a product
                // ("upgrade to the latest iphone") stays middle-band, not forced.
                "(latest|breaking|recent)\\s+(news|headlines)|" +
                // FUTURE
                "tomorrow|upcoming|next\\s+(hour|week|weekend|month|year)|" +
                "later\\s+(today|tonight|this\\s+week)"

        // Union of the two arms — unchanged behaviour for matches()/#38.
        val RELATIVE_TEMPORAL_PATTERN: Regex = Regex("\\b($PAST|$PRESENT_FUTURE)\\b")

        // PAST arm only — backs matchesPast().
        val PAST_PATTERN: Regex = Regex("\\b($PAST)\\b")
    }
}
