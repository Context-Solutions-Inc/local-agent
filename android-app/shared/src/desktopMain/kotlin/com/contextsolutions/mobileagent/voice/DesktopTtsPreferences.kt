package com.contextsolutions.mobileagent.voice

import com.contextsolutions.mobileagent.platform.DesktopJsonStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop [TtsPreferences] actual (docs/DESKTOP_PORT_PLAN.md Phase 9) — the
 * read-aloud toggle persisted in a [DesktopJsonStore] file, default `false`
 * (matching Android's `SharedPreferencesTtsPreferences`).
 */
class DesktopTtsPreferences(private val store: DesktopJsonStore) : TtsPreferences {

    private val state = MutableStateFlow(
        store.getString(KEY_ENABLED)?.toBooleanStrictOrNull() ?: false,
    )

    override fun isEnabled(): Boolean = state.value
    override fun enabledFlow(): Flow<Boolean> = state.asStateFlow()
    override fun setEnabled(enabled: Boolean) {
        if (state.value == enabled) return
        state.value = enabled
        store.putString(KEY_ENABLED, enabled.toString())
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
    }
}
