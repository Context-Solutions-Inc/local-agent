package com.contextsolutions.localagent.onboarding

import com.contextsolutions.localagent.platform.DesktopJsonStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop [OnboardingPreferences] (docs/DESKTOP_PORT_PLAN.md, Phase 7), the
 * counterpart of Android's `SharedPreferencesOnboardingPreferences`. Five
 * independent first-run gates persisted as plain booleans in a [DesktopJsonStore]
 * file (non-secret — "user was shown the X option"). State is seeded from disk
 * at construction; mutations update both the in-memory [MutableStateFlow] (so
 * subscribers see the change synchronously) and the JSON file (so the next
 * process recovers it).
 */
class DesktopOnboardingPreferences(private val store: DesktopJsonStore) : OnboardingPreferences {

    private val disclosureState = MutableStateFlow(readBool(KEY_DISCLOSURE_ACKED))
    private val braveKeyState = MutableStateFlow(readBool(KEY_BRAVE_KEY_DECIDED))
    private val hfAuthTokenState = MutableStateFlow(readBool(KEY_HF_AUTH_TOKEN_DECIDED))
    private val locationState = MutableStateFlow(readBool(KEY_LOCATION_DECIDED))

    override fun disclosureAcknowledged(): Boolean = disclosureState.value
    override fun disclosureAcknowledgedFlow(): Flow<Boolean> = disclosureState.asStateFlow()
    override fun markDisclosureAcknowledged() = mark(disclosureState, KEY_DISCLOSURE_ACKED)

    override fun braveKeyDecided(): Boolean = braveKeyState.value
    override fun braveKeyDecidedFlow(): Flow<Boolean> = braveKeyState.asStateFlow()
    override fun markBraveKeyDecided() = mark(braveKeyState, KEY_BRAVE_KEY_DECIDED)

    override fun hfAuthTokenDecided(): Boolean = hfAuthTokenState.value
    override fun hfAuthTokenDecidedFlow(): Flow<Boolean> = hfAuthTokenState.asStateFlow()
    override fun markHfAuthTokenDecided() = mark(hfAuthTokenState, KEY_HF_AUTH_TOKEN_DECIDED)

    override fun locationDecided(): Boolean = locationState.value
    override fun locationDecidedFlow(): Flow<Boolean> = locationState.asStateFlow()
    override fun markLocationDecided() = mark(locationState, KEY_LOCATION_DECIDED)

    private fun readBool(key: String): Boolean =
        store.getString(key)?.toBooleanStrictOrNull() ?: false

    private fun mark(state: MutableStateFlow<Boolean>, key: String) {
        if (state.value) return // idempotent
        state.value = true
        store.putString(key, "true")
    }

    private companion object {
        const val KEY_DISCLOSURE_ACKED = "disclosure_acknowledged"
        const val KEY_BRAVE_KEY_DECIDED = "brave_key_decided"
        const val KEY_HF_AUTH_TOKEN_DECIDED = "hf_auth_token_decided"
        const val KEY_LOCATION_DECIDED = "location_decided"
    }
}
