package com.contextsolutions.localagent.search

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Subset of the Brave Web Search response we consume. We model both the
 * `web.results[]` and `news.results[]` blocks — see `docs/BRAVE_SPIKE.md` for
 * the rationale on news. Ads, widgets, infobox, and the rest of Brave's
 * payload are discarded per PRD §3.3. The Ktor `Json` configuration ignores
 * unknown keys, so additive Brave changes don't break us.
 *
 * Reference: https://api.search.brave.com/app/documentation/web-search/responses
 */
@Serializable
data class BraveSearchResponse(
    val web: BraveWebResults? = null,
    val news: BraveNewsResults? = null,
)

@Serializable
data class BraveWebResults(
    val results: List<BraveResult> = emptyList(),
)

/**
 * `description` is Brave's snippet field. It often contains inline HTML
 * (`<strong>` around match terms); the post-processor strips tags before
 * truncating to the snippet budget.
 */
@Serializable
data class BraveResult(
    val title: String = "",
    val url: String = "",
    val description: String = "",
)

@Serializable
data class BraveNewsResults(
    val results: List<BraveNewsResult> = emptyList(),
)

/**
 * `news.results[]` carries a few signals `web.results[]` does not:
 *  - [age] — news-cycle relative freshness ("6 hours ago"), accurate where
 *    `web.results.age` reflects static page-publish dates.
 *  - [pageAge] — ISO timestamp; used as a tiebreaker by [SearchPostProcessor]
 *    when ranking news hits.
 *  - [breaking] — Brave-classified breaking-news flag. Surfaced to the model
 *    via [SearchSource.breaking] so the answer can reflect freshness.
 *
 * All recency fields are nullable because they appear inconsistently across
 * news entries from older indexes.
 */
@Serializable
data class BraveNewsResult(
    val title: String = "",
    val url: String = "",
    val description: String = "",
    val age: String? = null,
    @SerialName("page_age") val pageAge: String? = null,
    val breaking: Boolean = false,
    @SerialName("is_live") val isLive: Boolean = false,
)
