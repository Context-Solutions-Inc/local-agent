package com.contextsolutions.mobileagent.search.vertical

import com.contextsolutions.mobileagent.platform.HttpEngineFactory
import com.contextsolutions.mobileagent.search.SearchService
import com.contextsolutions.mobileagent.search.SearchSubtype
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent

/**
 * Builds a fully-wired [VerticalSearchDispatcher] without leaking Ktor
 * types to the Hilt module in `:androidApp`. Mirrors the pattern used by
 * [com.contextsolutions.mobileagent.search.KtorBraveSearchClient] which
 * takes an [HttpEngineFactory] and constructs its [io.ktor.client.HttpClient]
 * internally — the Android DI graph is Ktor-free.
 *
 * Adapters share a single HttpClient with a browser-shaped UA and a 10s
 * request timeout. Many RSS/HTML providers block default Ktor UAs
 * (`Ktor http-client`); presenting as a real browser keeps fetches stable.
 */
object VerticalSearchDispatcherFactory {

    fun create(
        httpEngineFactory: HttpEngineFactory,
        searchService: SearchService,
        sportsSearchService: SearchService = searchService,
        financeSearchService: SearchService = searchService,
        newsSearchService: SearchService = searchService,
        logger: (String) -> Unit = {},
    ): VerticalSearchDispatcher {
        val client = httpEngineFactory.create {
            install(UserAgent) { agent = USER_AGENT }
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
            }
        }
        val rssParser = RssParser()
        val readability = HtmlReadabilityExtractor()
        val adapters: Map<SearchSubtype, VerticalSearchAdapter> = mapOf(
            // NEWS routes per-source-kind through a composite (PR #47): the
            // FeedAdapter handles RSS/DWML/HTML/JSON sources, the
            // BraveSiteFilterAdapter handles BRAVE_SITE_FILTER sources, and
            // NewsKindRoutingAdapter merges. This lets NEWS exercise all five
            // SourceKind parsers — pre-#47 it was Brave-only and silently
            // dropped the other four kinds (see invariant #31). The Brave side
            // keeps its own LLM-context service ([newsSearchService], maxUrls =
            // 10) and up to 10 citation chips (PR #42); maxDomains stays at the
            // default (3) so the multi-site OR filter covers the shipped US
            // defaults (apnews.com + reuters.com) plus any source the user adds.
            SearchSubtype.NEWS to NewsKindRoutingAdapter(
                feedAdapter = FeedAdapter(
                    subtype = SearchSubtype.NEWS,
                    httpClient = client,
                    rssParser = rssParser,
                    readability = readability,
                    logger = logger,
                ),
                braveAdapter = BraveSiteFilterAdapter(
                    searchService = newsSearchService,
                    subtype = SearchSubtype.NEWS,
                    maxCitations = 10,
                    logger = logger,
                ),
                maxMergedCitations = 10,
                logger = logger,
            ),
            SearchSubtype.WEATHER to FeedAdapter(
                subtype = SearchSubtype.WEATHER,
                httpClient = client,
                rssParser = rssParser,
                readability = readability,
                logger = logger,
            ),
            // SPORTS uses Brave's LLM Context endpoint (PR #41) via
            // [sportsSearchService] — pre-extracted, relevance-ranked page
            // content instead of the index snippets `/web/search` returns —
            // AND pins the query to the user's preferred sports domain with a
            // `site:` filter. The PR #41 "unpinned" bet failed on-device: the
            // unpinned endpoint returned nba.com video clips and a sportingnews
            // schedule table as the top sources, with only espn.com carrying
            // the actual scores — burying the answer in noise the 2B model then
            // mis-transcribed. Pinning to one site (espn.com / tsn.ca per
            // search_defaults.json) gives the model a single clean snippet.
            // maxDomains/maxCitations = 1: one source, one citation chip.
            SearchSubtype.SPORTS to BraveSiteFilterAdapter(
                searchService = sportsSearchService,
                subtype = SearchSubtype.SPORTS,
                maxDomains = 1,
                maxCitations = 1,
                logger = logger,
            ),
            // FINANCE resolves the ticker via Brave's finance result, then
            // fetches stockanalysis.com for a structured quote rendered as a
            // deterministic card (PR #38). On no quote it falls back to the
            // `site:`-filtered web search (PR #35 — one source + one citation,
            // same rationale as SPORTS). Uses [financeSearchService] — Brave
            // `/web/search`, NOT `/llm/context` (PR #42): ticker resolution parses
            // the `finance.yahoo.com/quote/<TICKER>/` URL, which only `/web/search`
            // returns (`/llm/context` returns `/markets/stocks/articles/…`). See #37.
            SearchSubtype.FINANCE to FinanceQuoteAdapter(
                httpClient = client,
                fallback = BraveSiteFilterAdapter(
                    searchService = financeSearchService,
                    subtype = SearchSubtype.FINANCE,
                    maxDomains = 1,
                    maxCitations = 1,
                    logger = logger,
                ),
                logger = logger,
            ),
        )
        return VerticalSearchDispatcher(
            adapters = adapters,
            generalAdapter = GeneralSearchAdapter(searchService),
        )
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 16; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Mobile Safari/537.36 MobileAgent/1.0"
    private const val REQUEST_TIMEOUT_MS = 10_000L
    private const val CONNECT_TIMEOUT_MS = 5_000L
    private const val SOCKET_TIMEOUT_MS = 10_000L
}
