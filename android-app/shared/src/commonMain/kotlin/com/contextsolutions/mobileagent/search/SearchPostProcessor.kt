package com.contextsolutions.mobileagent.search

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Reduces a Brave Web Search response to the budget-bounded payload defined in
 * PRD §3.3: top-5 results, snippets ≤200 chars (HTML tags stripped), total
 * formatted JSON ≤4KB. The cap is enforced by progressively shortening
 * snippets and, as a last resort, dropping trailing results — this keeps the
 * agent's token budget safe even on pathological queries.
 *
 * **News-aware merging** (docs/BRAVE_SPIKE.md §7). When the response carries
 * `news.results[]` with at least [NEWS_SHAPED_THRESHOLD] usable entries the
 * query is treated as news-shaped, and news fills all 5 slots when available —
 * preferring `breaking == true` then ISO `page_age` — with web hits topping
 * up any remaining slots. The threshold avoids polluting non-news queries
 * (e.g., `NVDA stock price` returns 0 news hits and renders identically to
 * today).
 */
object SearchPostProcessor {
    private const val MAX_SNIPPET_CHARS = 200
    private const val MAX_PAYLOAD_BYTES = 4 * 1024
    private const val TOP_N = 5
    private const val MIN_SNIPPET_CHARS = 40 // floor when shrinking to fit the byte cap
    private const val NEWS_SHAPED_THRESHOLD = 3
    private const val MAX_NEWS_IN_TOP_N = 5

    private val htmlTagRegex = Regex("<[^>]*>")
    private val whitespaceRegex = Regex("\\s+")

    private val json = Json {
        encodeDefaults = false
        prettyPrint = false
    }
    private val sourceListSerializer = ListSerializer(SearchSource.serializer())

    fun format(response: BraveSearchResponse): FormattedSearchPayload {
        val validWeb = response.web?.results.orEmpty()
            .filter { it.url.isNotBlank() && it.title.isNotBlank() }
        val validNews = response.news?.results.orEmpty()
            .filter { it.url.isNotBlank() && it.title.isNotBlank() }

        val raw = mergeSources(validWeb, validNews).toMutableList()

        var encoded = json.encodeToString(sourceListSerializer, raw)
        // Shrink snippets uniformly until we fit, then drop trailing results if
        // even minimum-length snippets blow the budget (rare on a top-5 cap
        // with the 4KB budget).
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

    /**
     * News-shaped: fill from news first (up to [MAX_NEWS_IN_TOP_N], sorted by
     * breaking then ISO `page_age` desc), then top up any remaining slots from
     * web, then fall back to leftover news if web is short. Dedup by URL
     * across the two streams so the same article doesn't appear twice when
     * Brave indexes it in both blocks.
     *
     * Non-news-shaped: top-5 web hits.
     */
    private fun mergeSources(web: List<BraveResult>, news: List<BraveNewsResult>): List<SearchSource> {
        if (news.size < NEWS_SHAPED_THRESHOLD) {
            return web.asSequence()
                .take(TOP_N)
                .map { it.toSourceWithSnippet(MAX_SNIPPET_CHARS) }
                .toList()
        }

        val newsSorted = news.sortedWith(
            compareByDescending<BraveNewsResult> { it.breaking }
                .thenByDescending { it.pageAge ?: "" },
        )

        val picked = mutableListOf<SearchSource>()
        val takenUrls = mutableSetOf<String>()

        for (n in newsSorted.take(MAX_NEWS_IN_TOP_N)) {
            picked.add(n.toSourceWithSnippet(MAX_SNIPPET_CHARS))
            takenUrls.add(n.url.trim())
        }
        for (w in web) {
            if (picked.size >= TOP_N) break
            if (w.url.trim() in takenUrls) continue
            picked.add(w.toSourceWithSnippet(MAX_SNIPPET_CHARS))
            takenUrls.add(w.url.trim())
        }
        for (n in newsSorted.drop(MAX_NEWS_IN_TOP_N)) {
            if (picked.size >= TOP_N) break
            if (n.url.trim() in takenUrls) continue
            picked.add(n.toSourceWithSnippet(MAX_SNIPPET_CHARS))
            takenUrls.add(n.url.trim())
        }
        return picked
    }

    private fun BraveResult.toSourceWithSnippet(snippetLimit: Int): SearchSource =
        SearchSource(
            title = title.cleanForPayload(),
            url = url.trim(),
            snippet = description.cleanForPayload().truncate(snippetLimit),
        )

    private fun BraveNewsResult.toSourceWithSnippet(snippetLimit: Int): SearchSource =
        SearchSource(
            title = title.cleanForPayload(),
            url = url.trim(),
            snippet = description.cleanForPayload().truncate(snippetLimit),
            age = age,
            breaking = if (breaking) true else null,
        )

    private fun String.cleanForPayload(): String =
        replace(htmlTagRegex, "")
            .replace(whitespaceRegex, " ")
            .trim()

    private fun String.truncate(limit: Int): String =
        if (length <= limit) this else substring(0, limit).trimEnd() + "…"
}
