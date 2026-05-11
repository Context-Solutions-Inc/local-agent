package com.contextsolutions.mobileagent.onboarding

import kotlinx.coroutines.flow.Flow

/**
 * Persistent state for the M6 Phase E first-run onboarding flow.
 *
 * Three independent gates:
 *
 *  - [disclosureAcknowledged] — user has read and accepted the on-device
 *    privacy disclosure (PRD §6.1).
 *  - [braveKeyDecided] — user has either entered a Brave Search API key
 *    or explicitly chosen to skip ("Add later in Settings"). The
 *    presence-of-key check still drives the actual search-tool gating;
 *    this flag only tracks "user was shown the option".
 *  - [telemetryDecided] — mirrors `TelemetryConsentManager.firstRunDecided`
 *    from Phase C. Kept here as a read-through cache so the host can
 *    compute "is onboarding complete?" from a single state object.
 *
 * Onboarding is complete when all three are true. The download screen +
 * "ready" screen are sequenced after onboarding by `MainScreen`.
 *
 * Implementation lives in `:shared/androidMain` backed by a plain
 * SharedPreferences file (non-secret booleans; same pattern as
 * MemoryPreferences + SharedPreferencesTelemetryConsentManager).
 */
interface OnboardingPreferences {

    fun disclosureAcknowledged(): Boolean
    fun disclosureAcknowledgedFlow(): Flow<Boolean>
    fun markDisclosureAcknowledged()

    fun braveKeyDecided(): Boolean
    fun braveKeyDecidedFlow(): Flow<Boolean>
    fun markBraveKeyDecided()
}
