package com.contextsolutions.localagent.onboarding

import kotlinx.coroutines.flow.Flow

/**
 * Persistent state for the M6 Phase E first-run onboarding flow.
 *
 * Three independent gates (PR #22 dropped the Brave-key, HuggingFace-token, and
 * telemetry-consent onboarding steps — Brave/search and telemetry are now opt-in
 * from Settings, and model downloads no longer need an HF token):
 *
 *  - [languageDecided] (PR #97) — user has picked the app/response language on
 *    the very first onboarding screen (or kept the default). The language
 *    itself is persisted by `LanguagePreferences`; this flag only tracks "user
 *    was shown the picker", and it gates BEFORE every other step so the rest of
 *    onboarding renders in the chosen language.
 *  - [disclosureAcknowledged] — user has read and accepted the on-device
 *    privacy disclosure (PRD §6.1).
 *  - [locationDecided] (PR #23) — user has either captured a
 *    country/region/city for vertical search routing, or explicitly
 *    accepted the device-locale fallback. The location itself is
 *    persisted by `SearchPreferencesRepository`; this flag only tracks
 *    "user was shown the picker".
 *
 * Onboarding is complete when all three are true. The download screen +
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

    fun locationDecided(): Boolean
    fun locationDecidedFlow(): Flow<Boolean>
    fun markLocationDecided()
}
