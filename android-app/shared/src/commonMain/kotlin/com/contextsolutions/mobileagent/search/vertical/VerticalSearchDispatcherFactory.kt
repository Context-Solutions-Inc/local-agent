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
            SearchSubtype.NEWS to BraveSiteFilterAdapter(searchService = searchService),
            SearchSubtype.WEATHER to FeedAdapter(
                subtype = SearchSubtype.WEATHER,
                httpClient = client,
                rssParser = rssParser,
                readability = readability,
                logger = logger,
            ),
            // SPORTS uses Brave with a `site:` filter (PR #34) rather than RSS:
            // RSS feeds only carry recent headlines and can't answer historical
            // queries like "who won the masters last year".
            SearchSubtype.SPORTS to BraveSiteFilterAdapter(
                searchService = searchService,
                subtype = SearchSubtype.SPORTS,
            ),
            SearchSubtype.FINANCE to FeedAdapter(
                subtype = SearchSubtype.FINANCE,
                httpClient = client,
                rssParser = rssParser,
                readability = readability,
                logger = logger,
            ),
            SearchSubtype.STOCKS to StockLookupAdapter(
                httpClient = client,
                readability = readability,
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
