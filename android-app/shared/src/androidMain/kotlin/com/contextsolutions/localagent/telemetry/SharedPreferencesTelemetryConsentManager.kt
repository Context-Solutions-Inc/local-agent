package com.contextsolutions.localagent.telemetry

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android [TelemetryConsentManager] backed by a non-encrypted
 * `SharedPreferences` file. The toggle and the first-run flag are non-secret
 * (they're privacy preferences, not credentials), so the encryption layer
 * used for the Brave API key is unnecessary here.
 *
 * Reactive reads are served from in-memory [MutableStateFlow]s seeded from
 * disk at construction time. Writes update both the StateFlow (for
 * subscribers) and the SharedPreferences file (for next process start).
 */
class SharedPreferencesTelemetryConsentManager(context: Context) : TelemetryConsentManager {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val enabledState = MutableStateFlow(
        prefs.getBoolean(KEY_ENABLED, TelemetryConsentManager.DEFAULT_ENABLED),
    )
    private val firstRunState = MutableStateFlow(
        prefs.getBoolean(KEY_FIRST_RUN_DECIDED, false),
    )

    override fun enabled(): Boolean = enabledState.value

    override fun enabledFlow(): Flow<Boolean> = enabledState.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        enabledState.value = enabled
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    override fun firstRunDecided(): Boolean = firstRunState.value

    override fun firstRunDecidedFlow(): Flow<Boolean> = firstRunState.asStateFlow()

    override fun markFirstRunDecided() {
        if (firstRunState.value) return // idempotent
        firstRunState.value = true
        prefs.edit().putBoolean(KEY_FIRST_RUN_DECIDED, true).apply()
    }

    private companion object {
        private const val PREFS_NAME = "telemetry_consent"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_FIRST_RUN_DECIDED = "first_run_decided"
    }
}
