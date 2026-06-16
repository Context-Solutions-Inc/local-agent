package com.contextsolutions.localagent.search

/**
 * Two TTL bands per PRD §3.4: time-sensitive queries expire after 5 minutes;
 * everything else after 1 hour. The classifier is a coarse keyword check —
 * the pre-flight classifier (PRD §3.2.1, M3/M4) is the precise one. This is
 * just enough to keep `score price weather`-style queries from serving stale
 * results to a returning user 90 minutes later.
 */
enum class SearchCacheCategory(val ttlMs: Long) {
    TIME_SENSITIVE(5 * 60 * 1_000L),
    GENERAL(1 * 60 * 60 * 1_000L);

    val storageId: String
        get() = when (this) {
            TIME_SENSITIVE -> "time_sensitive"
            GENERAL -> "general"
        }
}

object SearchCacheClassifier {
    // Whole-word matching against a lowercased query. PRD §3.4 spells out the
    // canonical tokens; we add common plurals/inflections that share the same
    // recency intent (a user asking about "scores" wants today's, same as
    // "score").
    private val timeSensitiveTokens = setOf(
        "now", "today", "current", "currently",
        "score", "scores", "price", "prices",
        "weather", "forecast",
    )

    private val tokenSplit = Regex("[^a-z0-9]+")

    fun categorize(query: String): SearchCacheCategory {
        val tokens = query.lowercase().split(tokenSplit).filter { it.isNotEmpty() }
        return if (tokens.any { it in timeSensitiveTokens }) {
            SearchCacheCategory.TIME_SENSITIVE
        } else {
            SearchCacheCategory.GENERAL
        }
    }
}

/**
 * Cache-key normalization per PRD §3.4: lowercased, whitespace-collapsed. Keeps
 * "Who Won The Eagles Game" and "who won the eagles game " mapping to the same
 * row.
 */
object SearchCacheKey {
    private val whitespaceRegex = Regex("\\s+")

    fun normalize(query: String): String =
        query.trim().lowercase().replace(whitespaceRegex, " ")
}
