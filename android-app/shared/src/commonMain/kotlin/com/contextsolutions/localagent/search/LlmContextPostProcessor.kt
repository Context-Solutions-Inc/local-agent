package com.contextsolutions.localagent.search

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Reduces a Brave **LLM Context** response ([BraveLlmContextResponse]) to the
 * budget-bounded [FormattedSearchPayload] the agent loop hands the model and
 * the UI renders as citation chips. The web-search counterpart is
 * [SearchPostProcessor]; this mirrors its shrink-to-fit shape but is tuned for
 * the richer, page-extracted content the LLM Context endpoint returns.
 *
 * Each `grounding.generic[]` entry becomes one [SearchSource]: `title`/`url`
 * pass through, and the entry's `snippets[]` (the extracted page-content
 * chunks) are joined into the `snippet` field. `age` is pulled from the
 * top-level `sources` map (preferring a relative "… ago" form) so a chip can
 * show freshness, mirroring the news path.
 *
 * **Budget.** Because content depth is the whole point of this endpoint we
 * allow a larger per-source snippet ([MAX_SNIPPET_CHARS]) and a larger total
 * ([MAX_PAYLOAD_BYTES]) than the 200-char / 4 KB web path — but still bounded,
 * so the on-device Gemma 4 E2B context stays small. The server-side
 * `maximum_number_of_tokens` / `maximum_number_of_urls` request caps
 * (see [KtorBraveLlmContextClient]) are the first line of defence; this is the
 * client-side backstop.
 */
object LlmContextPostProcessor {
    private const val MAX_SNIPPET_CHARS = 600
    private const val MAX_PAYLOAD_BYTES = 6 * 1024
    private const val MIN_SNIPPET_CHARS = 80 // floor when shrinking to fit the byte cap

    private val htmlTagRegex = Regex("<[^>]*>")
    private val whitespaceRegex = Regex("\\s+")

    private val json = Json {
        encodeDefaults = false
        prettyPrint = false
    }
    private val sourceListSerializer = ListSerializer(SearchSource.serializer())

    fun format(response: BraveLlmContextResponse): FormattedSearchPayload {
        val raw = response.grounding?.generic.orEmpty()
            .filter { it.url.isNotBlank() && it.title.isNotBlank() }
            .map { it.toSource(response.sources[it.url], MAX_SNIPPET_CHARS) }
            .filter { it.snippet.isNotBlank() }
            .toMutableList()

        var encoded = json.encodeToString(sourceListSerializer, raw)
        // Shrink snippets uniformly until we fit, then drop trailing sources if
        // even minimum-length snippets blow the budget.
        var snippetLimit = MAX_SNIPPET_CHARS
        while (encoded.encodeToByteArray().size > MAX_PAYLOAD_BYTES && snippetLimit > MIN_SNIPPET_CHARS) {
            snippetLimit = (snippetLimit - 60).coerceAtLeast(MIN_SNIPPET_CHARS)
            for (i in raw.indices) raw[i] = raw[i].copy(snippet = raw[i].snippet.truncate(snippetLimit))
            encoded = json.encodeToString(sourceListSerializer, raw)
        }
        while (encoded.encodeToByteArray().size > MAX_PAYLOAD_BYTES && raw.isNotEmpty()) {
            raw.removeAt(raw.lastIndex)
            encoded = json.encodeToString(sourceListSerializer, raw)
        }

        return FormattedSearchPayload(json = encoded, sources = raw.toList())
    }

    private fun BraveGroundingEntry.toSource(meta: BraveLlmSource?, snippetLimit: Int): SearchSource =
        SearchSource(
            title = title.cleanForPayload(),
            url = url.trim(),
            // Keep only prose chunks. Brave's LLM Context may return a snippet
            // as JSON-serialized structured data (a VideoObject blob, a
            // standings/schedule table) — those carry hex IDs, timestamps, and
            // codes that bury the prose scores and that the 2B model
            // mis-transcribes. Dropping them leaves the readable text only; if
            // an entry was ALL JSON it ends up blank and is filtered out by the
            // caller's isNotBlank() guard.
            snippet = snippets
                .filterNot { it.isLikelyJson() }
                .joinToString(separator = "\n") { it.cleanForPayload() }
                .trim()
                .truncate(snippetLimit),
            age = meta?.age?.pickFreshness(),
        )

    /** A snippet whose trimmed form opens with `{`/`[` is serialized structured data, not prose. */
    private fun String.isLikelyJson(): Boolean {
        val t = trimStart()
        return t.startsWith("{") || t.startsWith("[")
    }

    /**
     * Brave returns `age` as multiple formats (absolute long, ISO, relative).
     * Prefer the relative "… ago" form for a freshness chip, matching the news
     * path's `age` semantics; fall back to the first non-blank entry.
     */
    private fun List<String>.pickFreshness(): String? =
        firstOrNull { it.contains("ago", ignoreCase = true) }
            ?: firstOrNull { it.isNotBlank() }

    private fun String.cleanForPayload(): String =
        replace(htmlTagRegex, "")
            .replace(whitespaceRegex, " ")
            .trim()

    private fun String.truncate(limit: Int): String =
        if (length <= limit) this else substring(0, limit).trimEnd() + "…"
}
