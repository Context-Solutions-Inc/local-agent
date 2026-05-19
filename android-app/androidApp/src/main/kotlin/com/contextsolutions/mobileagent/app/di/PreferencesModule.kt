package com.contextsolutions.mobileagent.app.di

import android.content.Context
import android.util.Log
import com.contextsolutions.mobileagent.preferences.DataStoreSearchPreferencesRepository
import com.contextsolutions.mobileagent.preferences.DefaultSiteResolver
import com.contextsolutions.mobileagent.preferences.SearchPreferencesRepository
import com.contextsolutions.mobileagent.preferences.VerticalPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * PR #23 — vertical search preferences wiring.
 *
 * - [DefaultSiteResolver] is parsed from `search_defaults.json` in assets,
 *   per invariant #14 (config lives in JSON, not Kotlin). Parse failure
 *   falls back to an empty resolver (verticals will report "no sources
 *   configured" errors, which the agent surfaces as graceful degradation —
 *   no crash).
 * - [SearchPreferencesRepository] is the DataStore-backed impl.
 */
@Module
@InstallIn(SingletonComponent::class)
object PreferencesModule {

    private const val DEFAULTS_ASSET = "search_defaults.json"
    private const val TAG = "PreferencesModule"

    @Provides
    @Singleton
    fun provideDefaultSiteResolver(@ApplicationContext context: Context): DefaultSiteResolver = try {
        val raw = context.assets.open(DEFAULTS_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        DefaultSiteResolver(raw)
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to load $DEFAULTS_ASSET; vertical defaults will be empty (${t.message})")
        DefaultSiteResolver(EMPTY_DEFAULTS_JSON)
    }

    @Provides
    @Singleton
    fun provideSearchPreferencesRepository(
        @ApplicationContext context: Context,
        resolver: DefaultSiteResolver,
    ): SearchPreferencesRepository = DataStoreSearchPreferencesRepository(
        context = context,
        resolver = resolver,
    )

    private const val EMPTY_DEFAULTS_JSON = """{"fallback":"US","countries":{"US":{}}}"""
}
