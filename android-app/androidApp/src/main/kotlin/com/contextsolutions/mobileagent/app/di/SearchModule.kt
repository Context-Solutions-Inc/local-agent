package com.contextsolutions.mobileagent.app.di

import com.contextsolutions.mobileagent.app.BuildConfig
import com.contextsolutions.mobileagent.db.SearchCacheQueries
import com.contextsolutions.mobileagent.platform.AgentClock
import com.contextsolutions.mobileagent.platform.HttpEngineFactory
import com.contextsolutions.mobileagent.platform.SecureStorage
import com.contextsolutions.mobileagent.platform.SecureStorageKeys
import com.contextsolutions.mobileagent.search.BraveKeyProvider
import com.contextsolutions.mobileagent.search.BraveSearchClient
import com.contextsolutions.mobileagent.search.DefaultBraveKeyProvider
import com.contextsolutions.mobileagent.search.KtorBraveSearchClient
import com.contextsolutions.mobileagent.search.SearchCacheDao
import com.contextsolutions.mobileagent.search.SearchService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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
@Module
@InstallIn(SingletonComponent::class)
object SearchModule {

    @Provides
    @Singleton
    fun provideBraveKeyProvider(secureStorage: SecureStorage): BraveKeyProvider {
        val devKey = if (BuildConfig.INTERNAL_BUILD) BuildConfig.BRAVE_DEV_KEY else null
        return DefaultBraveKeyProvider(secureStorage, devKey)
    }

    @Provides
    @Singleton
    fun provideBraveSearchClient(httpEngineFactory: HttpEngineFactory): BraveSearchClient =
        KtorBraveSearchClient(httpEngineFactory)

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
    ): SearchService = SearchService(
        keyProvider = keyProvider,
        client = client,
        cache = cache,
        // Default ON; only the explicit string "false" disables. UI writes
        // "true"/"false" via SettingsViewModel.
        isEnabled = { secureStorage.get(SecureStorageKeys.SEARCH_ENABLED) != "false" },
    )
}
