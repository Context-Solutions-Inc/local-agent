package com.contextsolutions.mobileagent.onboarding

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

    private val disclosureState = MutableStateFlow(
        prefs.getBoolean(KEY_DISCLOSURE_ACKED, false),
    )
    private val braveKeyState = MutableStateFlow(
        prefs.getBoolean(KEY_BRAVE_KEY_DECIDED, false),
    )

    override fun disclosureAcknowledged(): Boolean = disclosureState.value
    override fun disclosureAcknowledgedFlow(): Flow<Boolean> = disclosureState.asStateFlow()
    override fun markDisclosureAcknowledged() {
        if (disclosureState.value) return
        disclosureState.value = true
        prefs.edit().putBoolean(KEY_DISCLOSURE_ACKED, true).apply()
    }

    override fun braveKeyDecided(): Boolean = braveKeyState.value
    override fun braveKeyDecidedFlow(): Flow<Boolean> = braveKeyState.asStateFlow()
    override fun markBraveKeyDecided() {
        if (braveKeyState.value) return
        braveKeyState.value = true
        prefs.edit().putBoolean(KEY_BRAVE_KEY_DECIDED, true).apply()
    }

    private companion object {
        private const val PREFS_NAME = "onboarding"
        private const val KEY_DISCLOSURE_ACKED = "disclosure_acknowledged"
        private const val KEY_BRAVE_KEY_DECIDED = "brave_key_decided"
    }
}
