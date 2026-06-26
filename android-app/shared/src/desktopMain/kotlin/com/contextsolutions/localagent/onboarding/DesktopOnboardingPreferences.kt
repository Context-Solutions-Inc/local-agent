package com.contextsolutions.localagent.onboarding

import com.contextsolutions.localagent.platform.DesktopJsonStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop [OnboardingPreferences] (docs/DESKTOP_PORT_PLAN.md, Phase 7), the
 * counterpart of Android's `SharedPreferencesOnboardingPreferences`. Two
 * independent first-run gates persisted as plain booleans in a [DesktopJsonStore]
 * file (non-secret — "user was shown the X option"). State is seeded from disk
 * at construction; mutations update both the in-memory [MutableStateFlow] (so
 * subscribers see the change synchronously) and the JSON file (so the next
 * process recovers it).
 */
class DesktopOnboardingPreferences(private val store: DesktopJsonStore) : OnboardingPreferences {

    private val languageState = MutableStateFlow(readBool(KEY_LANGUAGE_DECIDED))
    private val disclosureState = MutableStateFlow(readBool(KEY_DISCLOSURE_ACKED))

    override fun languageDecided(): Boolean = languageState.value
    override fun languageDecidedFlow(): Flow<Boolean> = languageState.asStateFlow()
    override fun markLanguageDecided() = mark(languageState, KEY_LANGUAGE_DECIDED)

    override fun disclosureAcknowledged(): Boolean = disclosureState.value
    override fun disclosureAcknowledgedFlow(): Flow<Boolean> = disclosureState.asStateFlow()
    override fun markDisclosureAcknowledged() = mark(disclosureState, KEY_DISCLOSURE_ACKED)

    private fun readBool(key: String): Boolean =
        store.getString(key)?.toBooleanStrictOrNull() ?: false

    private fun mark(state: MutableStateFlow<Boolean>, key: String) {
        if (state.value) return // idempotent
        state.value = true
        store.putString(key, "true")
    }

    private companion object {
        const val KEY_LANGUAGE_DECIDED = "language_decided"
        const val KEY_DISCLOSURE_ACKED = "disclosure_acknowledged"
    }
}
