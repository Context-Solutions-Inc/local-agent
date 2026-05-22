package com.contextsolutions.mobileagent.app.di

import android.util.Log
import com.contextsolutions.mobileagent.app.BuildConfig
import com.contextsolutions.mobileagent.db.SearchCacheQueries
import com.contextsolutions.mobileagent.platform.AgentClock
import com.contextsolutions.mobileagent.platform.HttpEngineFactory
import com.contextsolutions.mobileagent.platform.SecureStorage
import com.contextsolutions.mobileagent.platform.SecureStorageKeys
import com.contextsolutions.mobileagent.search.BraveKeyProvider
import com.contextsolutions.mobileagent.search.BraveSearchClient
import com.contextsolutions.mobileagent.search.DefaultBraveKeyProvider
import com.contextsolutions.mobileagent.search.KtorBraveLlmContextClient
import com.contextsolutions.mobileagent.search.KtorBraveSearchClient
import com.contextsolutions.mobileagent.search.SearchCacheDao
import com.contextsolutions.mobileagent.search.SearchService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Wires the search layer's commonMain seams into Hilt: key resolution, the Brave
 * HTTP client, the SQLite-backed cache, and the [SearchService] that composes
 * them. The dev key fallback is only honoured on internal builds; release
 * builds always pass `null`, so production users must enter their own key
 * (PRD §3.6 BYOK).
 *
 * The Ktor [HttpClient] backing the Brave client is constructed inside the
 * provider rather than published as its own binding — Brave is the only HTTP
 * caller in the agent (model download uses a separate OkHttpClient) and
 * keeping Ktor types out of `:androidApp` keeps the Hilt graph small.
 */
/**
 * Marks the SPORTS-tuned [SearchService] backed by Brave's LLM Context endpoint
 * (`/llm/context`). As of PR #42 the default [SearchService] is *also* on
 * `/llm/context`; this qualifier survives because SPORTS needs a distinct URL
 * budget (`maxUrls = 1`, invariant #35) and cache namespace from the GENERAL
 * default (`maxUrls = 3`).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SportsSearch

/**
 * Marks the NEWS [SearchService]: Brave LLM Context (`/llm/context`) like the
 * default, but with `maxUrls = 10` so the vertical can surface up to 10 citation
 * chips (`maxCitations = 10`) from its `site:`-pinned domain(s). Kept separate
 * from the GENERAL default (`maxUrls = 3`) so GENERAL's context size is
 * unaffected. See invariant #37.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class NewsSearch

/**
 * Marks the FINANCE [SearchService], which stays on Brave's `/web/search`
 * ([KtorBraveSearchClient]) — NOT `/llm/context`. `FinanceQuoteAdapter` resolves
 * a ticker by parsing the `finance.yahoo.com/quote/<TICKER>/` URL out of the
 * Brave results; `/llm/context` returns article URLs
 * (`/markets/stocks/articles/…`) with no `/quote/` segment, so ticker resolution
 * silently fails and the deterministic stockanalysis.com card never fires
 * (regressed when PR #42 first moved FINANCE onto `/llm/context`; reverted same
 * PR). See invariants #33 and #37.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FinanceSearch

@Module
@InstallIn(SingletonComponent::class)
object SearchModule {

    private const val TAG = "BraveApi"

    @Provides
    @Singleton
    fun provideBraveKeyProvider(secureStorage: SecureStorage): BraveKeyProvider {
        val devKey = if (BuildConfig.INTERNAL_BUILD) BuildConfig.BRAVE_DEV_KEY else null
        return DefaultBraveKeyProvider(secureStorage, devKey)
    }

    /**
     * The default Brave client for GENERAL. As of PR #42 this is the LLM Context
     * client (`/llm/context`) — pre-extracted page content instead of
     * `/web/search` index snippets — with `maxUrls = 3`. NEWS uses its own
     * 10-URL client ([provideNewsSearchService]); SPORTS its own 1-URL client
     * ([provideSportsSearchService]); FINANCE stays on `/web/search`
     * ([provideFinanceSearchService]) because its ticker resolution needs the
     * quote-page URL.
     */
    @Provides
    @Singleton
    fun provideBraveSearchClient(httpEngineFactory: HttpEngineFactory): BraveSearchClient =
        KtorBraveLlmContextClient(httpEngineFactory, maxUrls = 3) { Log.i(TAG, it) }

    @Provides
    @Singleton
    fun provideSearchCacheDao(
        queries: SearchCacheQueries,
        clock: AgentClock,
    ): SearchCacheDao = SearchCacheDao(queries = queries, nowEpochMs = clock::nowEpochMs)

    @Provides
    @Singleton
    fun provideSearchService(
        keyProvider: BraveKeyProvider,
        client: BraveSearchClient,
        cache: SearchCacheDao,
        secureStorage: SecureStorage,
        counters: com.contextsolutions.mobileagent.telemetry.TelemetryCounters,
    ): SearchService = SearchService(
        keyProvider = keyProvider,
        client = client,
        cache = cache,
        // Default ON; only the explicit string "false" disables. UI writes
        // "true"/"false" via SettingsViewModel.
        isEnabled = { secureStorage.get(SecureStorageKeys.SEARCH_ENABLED) != "false" },
        counters = counters,
        // Namespace the cache so pre-PR-#42 `/web/search`-shaped entries (cached
        // under the empty namespace) don't serve to the new `/llm/context`
        // client. Payload type is identical (FormattedSearchPayload) so there's
        // no deserialization break — this just drops stale-shaped content
        // immediately rather than waiting out the ≤1h TTL. Distinct from
        // SPORTS's "sports:" (different URL budget + site:-pin).
        cacheNamespace = "ctx:",
    )

    /**
     * SPORTS-tuned [SearchService] on Brave's LLM Context endpoint (PR #41).
     * Shares the key provider, cache DAO, enable-toggle, and counters with the
     * default service; differs only in URL budget (`maxUrls = 1`) and cache
     * namespace. The Ktor [io.ktor.client.HttpClient] is built inside
     * [KtorBraveLlmContextClient] so this module stays Ktor-free.
     */
    @Provides
    @Singleton
    @SportsSearch
    fun provideSportsSearchService(
        httpEngineFactory: HttpEngineFactory,
        keyProvider: BraveKeyProvider,
        cache: SearchCacheDao,
        secureStorage: SecureStorage,
        counters: com.contextsolutions.mobileagent.telemetry.TelemetryCounters,
    ): SearchService = SearchService(
        keyProvider = keyProvider,
        client = KtorBraveLlmContextClient(httpEngineFactory, maxUrls = 1) { Log.i(TAG, it) },
        cache = cache,
        isEnabled = { secureStorage.get(SecureStorageKeys.SEARCH_ENABLED) != "false" },
        counters = counters,
        // Namespace the shared cache so an unpinned SPORTS query can't collide
        // with an identical GENERAL query's `/web/search` payload (PR #41).
        cacheNamespace = "sports:",
    )

    /**
     * NEWS [SearchService] on Brave's LLM Context endpoint with `maxUrls = 10`
     * (vs the GENERAL default's 3) so the vertical can surface up to 10 citation
     * chips. Own cache namespace so its payloads don't mix with `"ctx:"`. See
     * [NewsSearch] and invariant #37.
     */
    @Provides
    @Singleton
    @NewsSearch
    fun provideNewsSearchService(
        httpEngineFactory: HttpEngineFactory,
        keyProvider: BraveKeyProvider,
        cache: SearchCacheDao,
        secureStorage: SecureStorage,
        counters: com.contextsolutions.mobileagent.telemetry.TelemetryCounters,
    ): SearchService = SearchService(
        keyProvider = keyProvider,
        client = KtorBraveLlmContextClient(httpEngineFactory, maxUrls = 10) { Log.i(TAG, it) },
        cache = cache,
        isEnabled = { secureStorage.get(SecureStorageKeys.SEARCH_ENABLED) != "false" },
        counters = counters,
        cacheNamespace = "news:",
    )

    /**
     * FINANCE [SearchService] on Brave's `/web/search` ([KtorBraveSearchClient]).
     * `FinanceQuoteAdapter` parses the `finance.yahoo.com/quote/<TICKER>/` result
     * URL to resolve a ticker, then fetches the stockanalysis.com card; only
     * `/web/search` returns that quote-page URL (`/llm/context` returns articles).
     * Own cache namespace so its `/web/search`-shaped payloads never mix with the
     * `"ctx:"` LLM-context default. See [FinanceSearch] and invariant #37.
     */
    @Provides
    @Singleton
    @FinanceSearch
    fun provideFinanceSearchService(
        httpEngineFactory: HttpEngineFactory,
        keyProvider: BraveKeyProvider,
        cache: SearchCacheDao,
        secureStorage: SecureStorage,
        counters: com.contextsolutions.mobileagent.telemetry.TelemetryCounters,
    ): SearchService = SearchService(
        keyProvider = keyProvider,
        client = KtorBraveSearchClient(httpEngineFactory) { Log.i(TAG, it) },
        cache = cache,
        isEnabled = { secureStorage.get(SecureStorageKeys.SEARCH_ENABLED) != "false" },
        counters = counters,
        cacheNamespace = "fin:",
    )
}
