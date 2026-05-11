package com.contextsolutions.mobileagent.app.di

import android.content.Context
import com.contextsolutions.mobileagent.onboarding.OnboardingPreferences
import com.contextsolutions.mobileagent.onboarding.SharedPreferencesOnboardingPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * M6 Phase E — first-run onboarding state.
 *
 * `OnboardingPreferences` tracks disclosure + Brave-key-decided across
 * launches so the user resumes mid-onboarding if they killed the app
 * partway through. Telemetry consent is tracked by the Phase C
 * `TelemetryConsentManager` (separate concern: Settings surfaces it
 * too).
 */
@Module
@InstallIn(SingletonComponent::class)
object OnboardingModule {

    @Provides
    @Singleton
    fun provideOnboardingPreferences(
        @ApplicationContext context: Context,
    ): OnboardingPreferences = SharedPreferencesOnboardingPreferences(context)
}
