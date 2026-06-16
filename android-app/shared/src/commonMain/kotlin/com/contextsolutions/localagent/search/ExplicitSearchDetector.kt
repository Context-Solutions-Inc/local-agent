package com.contextsolutions.localagent.search

/**
 * Detects an EXPLICIT web-search command at the START of a query — the user's
 * deterministic escape hatch when they want Brave regardless of what the
 * pre-flight classifier thinks ("web search the URL of the Android Open Source
 * Project"). Topic-agnostic — used by
 * [com.contextsolutions.localagent.classifier.PreflightRouter] to force the
 * FireSearch path, mirroring the [RelativeTemporalDetector] (#38) precedent.
 *
 * Why: the shipped classifier under-fires on plenty of phrasings (the reason the
 * high band keeps getting relaxed, invariant #14). A user who KNOWS they want a
 * live lookup had no way to say so. This gives them one.
 *
 * **Anchored, not contains.** Unlike [RelativeTemporalDetector] (which scans the
 * whole query with `containsMatchIn`), this matches ONLY a leading command. The
 * anchor is the false-positive guard: a user practically never *opens* a message
 * with "web search …" / "search the web …" / "search online …" unless they mean
 * it. "how do web search engines work" and "I did a web search yesterday" do NOT
 * fire because the command isn't at the front. The residual FP ("web search
 * engines explained") costs a wasted Brave round-trip — same accepted-FP policy
 * as the temporal detector, tracked via
 * [com.contextsolutions.localagent.telemetry.CounterNames.PREFLIGHT_EXPLICIT_SEARCH_FORCE_TOTAL].
 *
 * **Web-only triggers.** Deliberately NOT "google …" / "look up …" (a leading
 * "google announced …" or "look up to the sky" would mis-fire). Every accepted
 * trigger names the web/online explicitly.
 *
 * **Strip before searching.** The command words are not part of the actual
 * question, so [stripPrefix] removes them — the router runs the classifier,
 * rewriter, and [SearchSubtypeDetector] on the remainder, and Brave receives
 * "the URL of …", not "web search the URL of …".
 *
 * **Subordinate to the AgentLoop force-fires.** This lives in the router, which
 * runs only after the AgentLoop-level image / clock-todo / WEATHER short-circuits
 * (#32, #48). An explicit-search message that also carries an image, or that the
 * weather resolver claims, is handled by those higher-priority deterministic
 * paths — benign, since weather is itself a deterministic web fetch. See
 * invariant #43.
 */
class ExplicitSearchDetector {

    /** True when [query] opens with an explicit web-search command. */
    fun matches(query: String): Boolean =
        PREFIX_PATTERN.containsMatchIn(query.trimStart().lowercase())

    /**
     * Removes the leading command (+ a trailing "for"/":"/whitespace) and returns
     * the remaining query, trimmed. Operates on the original-case text so the
     * search query keeps its casing. Returns the original query unchanged when
     * [matches] is false OR stripping would leave nothing (the caller then fires
     * the verbatim query rather than an empty search).
     */
    fun stripPrefix(query: String): String {
        val trimmed = query.trimStart()
        val match = PREFIX_PATTERN.find(trimmed.lowercase()) ?: return query
        val remainder = trimmed.substring(match.range.last + 1).trim()
        return remainder.ifEmpty { query }
    }

    private companion object {
        // Anchored at start (^). Command alternatives all name the web/online
        // explicitly. The trailing group consumes the connective ("for"), a
        // colon, or the run of whitespace separating the command from the query,
        // so stripPrefix() lands cleanly on the question.
        val PREFIX_PATTERN: Regex = Regex(
            "^\\s*(web\\s*search|search\\s+(the\\s+web|online))(\\s+for\\b|\\s*:|\\s+)",
        )
    }
}
