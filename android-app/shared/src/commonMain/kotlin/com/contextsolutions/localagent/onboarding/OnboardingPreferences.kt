package com.contextsolutions.localagent.onboarding

import kotlinx.coroutines.flow.Flow

/**
 * Persistent state for the M6 Phase E first-run onboarding flow.
 *
 * Two independent gates (PR #22 dropped the Brave-key, HuggingFace-token, and
 * telemetry-consent onboarding steps; PR #23 dropped the country/location step —
 * Brave/search + telemetry are opt-in from Settings, model downloads need no HF
 * token, and the search-defaults country defaults to USA, changeable in Settings):
 *
 *  - [languageDecided] (PR #97) — user has picked the app/response language on
 *    the very first onboarding screen (or kept the default). The language
 *    itself is persisted by `LanguagePreferences`; this flag only tracks "user
 *    was shown the picker", and it gates BEFORE every other step so the rest of
 *    onboarding renders in the chosen language.
 *  - [disclosureAcknowledged] — user has read and accepted the on-device
 *    privacy disclosure (PRD §6.1).
 *
 * Onboarding is complete when both are true. The download screen +
 * "ready" screen are sequenced after onboarding by `MainScreen`.
 *
 * Implementation lives in `:shared/androidMain` backed by a plain
 * SharedPreferences file (non-secret booleans; same pattern as
 * MemoryPreferences + SharedPreferencesTelemetryConsentManager).
 */
interface OnboardingPreferences {

    fun languageDecided(): Boolean
    fun languageDecidedFlow(): Flow<Boolean>
    fun markLanguageDecided()

    fun disclosureAcknowledged(): Boolean
    fun disclosureAcknowledgedFlow(): Flow<Boolean>
    fun markDisclosureAcknowledged()
}
