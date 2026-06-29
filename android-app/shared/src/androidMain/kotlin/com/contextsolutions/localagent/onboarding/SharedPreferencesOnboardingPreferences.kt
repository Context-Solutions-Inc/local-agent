package com.contextsolutions.localagent.onboarding

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android [OnboardingPreferences] backed by a non-encrypted
 * `SharedPreferences` file. Same pattern as
 * `SharedPreferencesMemoryPreferences` and the consent manager.
 */
class SharedPreferencesOnboardingPreferences(context: Context) : OnboardingPreferences {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val languageState = MutableStateFlow(
        prefs.getBoolean(KEY_LANGUAGE_DECIDED, false),
    )

    override fun languageDecided(): Boolean = languageState.value
    override fun languageDecidedFlow(): Flow<Boolean> = languageState.asStateFlow()
    override fun markLanguageDecided() {
        if (languageState.value) return
        languageState.value = true
        prefs.edit().putBoolean(KEY_LANGUAGE_DECIDED, true).apply()
    }

    private companion object {
        private const val PREFS_NAME = "onboarding"
        private const val KEY_LANGUAGE_DECIDED = "language_decided"
    }
}
