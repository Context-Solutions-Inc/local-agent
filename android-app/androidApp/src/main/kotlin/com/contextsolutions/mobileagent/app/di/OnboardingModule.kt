package com.contextsolutions.mobileagent.app.di

import android.content.Context
import android.util.Log
import com.contextsolutions.mobileagent.onboarding.OnboardingPreferences
import com.contextsolutions.mobileagent.onboarding.SharedPreferencesOnboardingPreferences
import com.contextsolutions.mobileagent.preferences.LocationCatalog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * M6 Phase E — first-run onboarding state. PR #23 adds the
 * [LocationCatalog] used by the location-picker step.
 */
@Module
@InstallIn(SingletonComponent::class)
object OnboardingModule {

    private const val LOCATIONS_ASSET = "locations.json"
    private const val TAG = "OnboardingModule"

    @Provides
    @Singleton
    fun provideOnboardingPreferences(
        @ApplicationContext context: Context,
    ): OnboardingPreferences = SharedPreferencesOnboardingPreferences(context)

    @Provides
    @Singleton
    fun provideLocationCatalog(@ApplicationContext context: Context): LocationCatalog = try {
        val raw = context.assets.open(LOCATIONS_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        LocationCatalog(raw)
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to load $LOCATIONS_ASSET; location picker will be empty (${t.message})")
        LocationCatalog("""{"countries":[]}""")
    }
}
