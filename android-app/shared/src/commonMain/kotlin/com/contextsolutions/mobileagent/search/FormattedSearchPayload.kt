package com.contextsolutions.mobileagent.search

import kotlinx.serialization.Serializable

/**
 * Result of post-processing a [BraveSearchResponse] into the budget-bounded shape
 * the agent loop hands the model and the UI renders as citation chips.
 *
 * - [json] is a compact JSON array of `{title, url, snippet}` ≤2KB (PRD §3.3).
 *   The agent loop wraps this in a `web_search` tool_result.
 * - [sources] is the same data in typed form, used by the UI to render tappable
 *   citation chips with deep-links to the original URL (PRD §6.3).
 */
data class FormattedSearchPayload(
    val json: String,
    val sources: List<SearchSource>,
)

@Serializable
data class SearchSource(
    val title: String,
    val url: String,
    val snippet: String,
)
