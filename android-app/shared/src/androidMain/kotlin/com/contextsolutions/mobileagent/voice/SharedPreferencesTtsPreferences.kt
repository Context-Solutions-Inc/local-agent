package com.contextsolutions.mobileagent.voice

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android [TtsPreferences] actual — `SharedPreferences`-backed speaker toggle,
 * default `false`.
 */
class SharedPreferencesTtsPreferences(context: Context) : TtsPreferences {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))

    override fun isEnabled(): Boolean = state.value
    override fun enabledFlow(): Flow<Boolean> = state.asStateFlow()
    override fun setEnabled(enabled: Boolean) {
        if (state.value == enabled) return
        state.value = enabled
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private companion object {
        const val PREFS_NAME = "tts"
        const val KEY_ENABLED = "enabled"
    }
}
