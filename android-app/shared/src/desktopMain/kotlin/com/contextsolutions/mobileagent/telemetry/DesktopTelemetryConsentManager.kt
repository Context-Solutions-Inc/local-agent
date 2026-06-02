package com.contextsolutions.mobileagent.telemetry

import com.contextsolutions.mobileagent.platform.DesktopJsonStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop [TelemetryConsentManager] (docs/DESKTOP_PORT_PLAN.md, Phase 7), the
 * counterpart of Android's `SharedPreferencesTelemetryConsentManager`. Two
 * non-secret booleans — the opt-in toggle (default OFF per PRD §3.2.1, invariant
 * #24–#28) and the first-run-decided flag — persisted in a [DesktopJsonStore]
 * file. The desktop Sentry/file telemetry sink (a later Phase-7 increment) reads
 * [enabled] before every upload so flipping it OFF stops uploads on the next
 * cycle.
 */
class DesktopTelemetryConsentManager(private val store: DesktopJsonStore) : TelemetryConsentManager {

    private val enabledState = MutableStateFlow(
        store.getString(KEY_ENABLED)?.toBooleanStrictOrNull()
            ?: TelemetryConsentManager.DEFAULT_ENABLED,
    )
    private val firstRunState = MutableStateFlow(
        store.getString(KEY_FIRST_RUN_DECIDED)?.toBooleanStrictOrNull() ?: false,
    )

    override fun enabled(): Boolean = enabledState.value

    override fun enabledFlow(): Flow<Boolean> = enabledState.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        enabledState.value = enabled
        store.putString(KEY_ENABLED, enabled.toString())
    }

    override fun firstRunDecided(): Boolean = firstRunState.value

    override fun firstRunDecidedFlow(): Flow<Boolean> = firstRunState.asStateFlow()

    override fun markFirstRunDecided() {
        if (firstRunState.value) return // idempotent
        firstRunState.value = true
        store.putString(KEY_FIRST_RUN_DECIDED, "true")
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_FIRST_RUN_DECIDED = "first_run_decided"
    }
}
