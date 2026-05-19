package com.contextsolutions.mobileagent.app.di

import android.util.Log
import com.contextsolutions.mobileagent.platform.HttpEngineFactory
import com.contextsolutions.mobileagent.search.SearchService
import com.contextsolutions.mobileagent.search.SearchSubtypeDetector
import com.contextsolutions.mobileagent.search.vertical.VerticalSearchDispatcher
import com.contextsolutions.mobileagent.search.vertical.VerticalSearchDispatcherFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * PR #23 — vertical search wiring.
 *
 * The [VerticalSearchDispatcher] is built by [VerticalSearchDispatcherFactory]
 * inside `:shared` so this module never has to import Ktor types. Same
 * separation [SearchModule] uses for [com.contextsolutions.mobileagent.search.KtorBraveSearchClient].
 */
@Module
@InstallIn(SingletonComponent::class)
object VerticalSearchModule {

    private const val TAG = "VerticalSearch"

    @Provides
    @Singleton
    fun provideSearchSubtypeDetector(): SearchSubtypeDetector = SearchSubtypeDetector()

    @Provides
    @Singleton
    fun provideVerticalSearchDispatcher(
        httpEngineFactory: HttpEngineFactory,
        searchService: SearchService,
    ): VerticalSearchDispatcher = VerticalSearchDispatcherFactory.create(
        httpEngineFactory = httpEngineFactory,
        searchService = searchService,
        logger = { Log.i(TAG, it) },
    )
}
