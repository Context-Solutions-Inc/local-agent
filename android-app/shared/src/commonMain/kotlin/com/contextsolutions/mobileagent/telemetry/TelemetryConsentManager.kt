package com.contextsolutions.mobileagent.telemetry

import kotlinx.coroutines.flow.Flow

/**
 * User consent for opt-in aggregate telemetry per PRD §3.2.1 + §4.4.
 *
 * Two distinct concepts, deliberately separated:
 *
 *  - [enabled] / [setEnabled]: the current state of the user's opt-in. Read
 *    by [com.contextsolutions.mobileagent.telemetry.TelemetryUploader] on
 *    every fire — flipping to OFF stops uploads on the next cycle.
 *  - [firstRunDecided] / [markFirstRunDecided]: whether the user has been
 *    shown the consent screen at least once. Phase E onboarding uses this to
 *    gate "is onboarding done?" — the user must have either opted in OR
 *    actively dismissed the consent screen ("Skip") before we consider the
 *    decision made. Without this flag, we'd repeatedly nag the user on
 *    every fresh app launch.
 *
 * Default is OFF (PRD §3.2.1 explicit-opt-in requirement). The first-run
 * decision flag defaults to false; Phase E onboarding pre-pops the consent
 * screen on every cold start until it flips true.
 *
 * Implementation lives in `:shared/androidMain` backed by a non-encrypted
 * SharedPreferences file — the toggle is non-secret (it's a privacy
 * preference, not a credential), matching `MemoryPreferences`'s pattern.
 */
interface TelemetryConsentManager {

    /** Snapshot read of the user's opt-in. Safe to call from any dispatcher. */
    fun enabled(): Boolean

    /**
     * Reactive read of the user's opt-in. Emits the current value on
     * subscribe and then each subsequent change. Backed by an in-memory
     * StateFlow so collectors don't hit the SharedPreferences file on every
     * read.
     */
    fun enabledFlow(): Flow<Boolean>

    /** Persist the toggle. UI surfaces (Settings, Onboarding) call this. */
    fun setEnabled(enabled: Boolean)

    /** True once the user has been shown the first-run consent screen. */
    fun firstRunDecided(): Boolean

    /**
     * Reactive read of [firstRunDecided]. Phase E onboarding host observes
     * this to know when the consent step is complete.
     */
    fun firstRunDecidedFlow(): Flow<Boolean>

    /**
     * Called by the onboarding consent screen when the user selects either
     * "Help improve" (opt-in) or "Skip" (decline). Persisted so subsequent
     * cold starts skip the consent screen. Idempotent.
     */
    fun markFirstRunDecided()

    companion object {
        /** PRD §3.2.1: explicit-opt-in only. Default to OFF. */
        const val DEFAULT_ENABLED: Boolean = false
    }
}
