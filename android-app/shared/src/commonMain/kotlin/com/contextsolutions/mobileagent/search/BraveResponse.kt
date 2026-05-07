package com.contextsolutions.mobileagent.search

import kotlinx.serialization.Serializable

/**
 * Subset of the Brave Web Search response we consume. We deliberately model only
 * the `web.results[]` block — ads, widgets, infobox, and the rest of Brave's
 * payload are discarded per PRD §3.3. The Ktor `Json` configuration ignores
 * unknown keys, so additive Brave changes don't break us.
 *
 * Reference: https://api.search.brave.com/app/documentation/web-search/responses
 */
@Serializable
data class BraveSearchResponse(
    val web: BraveWebResults? = null,
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
