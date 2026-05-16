package com.contextsolutions.mobileagent.search

import kotlinx.serialization.Serializable

/**
 * Result of post-processing a [BraveSearchResponse] into the budget-bounded shape
 * the agent loop hands the model and the UI renders as citation chips.
 *
 * - [json] is a compact JSON array of `{title, url, snippet}` ≤4KB (PRD §3.3).
 *   The agent loop wraps this in a `web_search` tool_result.
 * - [sources] is the same data in typed form, used by the UI to render tappable
 *   citation chips with deep-links to the original URL (PRD §6.3).
 */
data class FormattedSearchPayload(
    val json: String,
    val sources: List<SearchSource>,
)

/**
 * [age] and [breaking] are populated only for hits drawn from Brave's
 * `news.results[]` block; standard web hits omit both fields. The post-
 * processor's `Json` serializer is configured with `encodeDefaults = false`,
 * so the JSON shape stays identical to today's `{title, url, snippet}` for
 * non-news payloads — old cache rows still deserialize cleanly.
 */
@Serializable
data class SearchSource(
    val title: String,
    val url: String,
    val snippet: String,
    val age: String? = null,
    val breaking: Boolean? = null,
)
