package com.contextsolutions.mobileagent.search

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Reduces a Brave Web Search response to the budget-bounded payload defined in
 * PRD §3.3: top-3 organic results, snippets ≤200 chars (HTML tags stripped),
 * total formatted JSON ≤2KB. The cap is enforced by progressively shortening
 * snippets and, as a last resort, dropping trailing results — this keeps the
 * agent's token budget safe even on pathological queries.
 */
object SearchPostProcessor {
    private const val MAX_SNIPPET_CHARS = 200
    private const val MAX_PAYLOAD_BYTES = 2 * 1024
    private const val TOP_N = 3
    private const val MIN_SNIPPET_CHARS = 40 // floor when shrinking to fit the byte cap

    private val htmlTagRegex = Regex("<[^>]*>")
    private val whitespaceRegex = Regex("\\s+")

    private val json = Json {
        encodeDefaults = false
        prettyPrint = false
    }
    private val sourceListSerializer = ListSerializer(SearchSource.serializer())

    fun format(response: BraveSearchResponse): FormattedSearchPayload {
        val raw = response.web?.results.orEmpty()
            .asSequence()
            .filter { it.url.isNotBlank() && it.title.isNotBlank() }
            .take(TOP_N)
            .map { it.toSourceWithSnippet(MAX_SNIPPET_CHARS) }
            .toMutableList()

        var encoded = json.encodeToString(sourceListSerializer, raw)
        // Shrink snippets uniformly until we fit, then drop trailing results if
        // even minimum-length snippets blow the budget (rare on a top-3 cap).
        var snippetLimit = MAX_SNIPPET_CHARS
        while (encoded.encodeToByteArray().size > MAX_PAYLOAD_BYTES && snippetLimit > MIN_SNIPPET_CHARS) {
            snippetLimit = (snippetLimit - 20).coerceAtLeast(MIN_SNIPPET_CHARS)
            for (i in raw.indices) raw[i] = raw[i].copy(snippet = raw[i].snippet.truncate(snippetLimit))
            encoded = json.encodeToString(sourceListSerializer, raw)
        }
        while (encoded.encodeToByteArray().size > MAX_PAYLOAD_BYTES && raw.isNotEmpty()) {
            raw.removeAt(raw.lastIndex)
            encoded = json.encodeToString(sourceListSerializer, raw)
        }

        return FormattedSearchPayload(json = encoded, sources = raw.toList())
    }

    private fun BraveResult.toSourceWithSnippet(snippetLimit: Int): SearchSource =
        SearchSource(
            title = title.cleanForPayload(),
            url = url.trim(),
            snippet = description.cleanForPayload().truncate(snippetLimit),
        )

    private fun String.cleanForPayload(): String =
        replace(htmlTagRegex, "")
            .replace(whitespaceRegex, " ")
            .trim()

    private fun String.truncate(limit: Int): String =
        if (length <= limit) this else substring(0, limit).trimEnd() + "…"
}
