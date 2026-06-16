package com.contextsolutions.localagent.search

import kotlinx.serialization.Serializable

/**
 * Subset of the Brave **LLM Context** response (`/res/v1/llm/context`) we
 * consume. Unlike `/web/search` (see [BraveSearchResponse]) this endpoint
 * returns *pre-extracted page content* rather than index snippets: each
 * [BraveGroundingEntry] carries the relevant text chunks lifted from the page,
 * already ranked and budget-trimmed server-side. SPORTS routes here (PR #41)
 * for richer, page-grounded answers; the rest of the verticals stay on
 * `/web/search`.
 *
 * We only model `grounding.generic[]` (the web-content chunks) and the
 * top-level `sources` map (used for citation chip metadata). `grounding.poi`
 * and `grounding.map` (local results) are ignored — the Ktor `Json` config
 * ignores unknown keys, so additive Brave changes don't break us.
 *
 * Reference: https://api-dashboard.search.brave.com/documentation/services/llm-context
 */
@Serializable
data class BraveLlmContextResponse(
    val grounding: BraveGrounding? = null,
    /**
     * Keyed by source URL. Inner [BraveLlmSource.age] is an array of date
     * formats (`["Monday, January 15, 2024", "2024-01-15", "380 days ago"]`)
     * or null; the post-processor picks the human-readable form for chips.
     */
    val sources: Map<String, BraveLlmSource> = emptyMap(),
)

@Serializable
data class BraveGrounding(
    val generic: List<BraveGroundingEntry> = emptyList(),
)

/**
 * One ranked source. [snippets] are the extracted page-content chunks (plain
 * text, or JSON-serialized tables/structured data) — this is the content depth
 * that distinguishes the LLM Context endpoint from a `/web/search` snippet.
 */
@Serializable
data class BraveGroundingEntry(
    val url: String = "",
    val title: String = "",
    val snippets: List<String> = emptyList(),
)

@Serializable
data class BraveLlmSource(
    val title: String = "",
    val hostname: String = "",
    val age: List<String> = emptyList(),
)
