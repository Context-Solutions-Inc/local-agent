package com.contextsolutions.localagent.voice

import com.contextsolutions.localagent.platform.DesktopJsonStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop [TtsPreferences] actual (docs/DESKTOP_PORT_PLAN.md Phase 9) — the
 * read-aloud toggle persisted in a [DesktopJsonStore] file, default `false`
 * (matching Android's `SharedPreferencesTtsPreferences`).
 *
 * Also owns the desktop-only read-aloud VOICE settings (PR #66): engine/voice/rate.
 * These stay on the concrete class — they're NOT on the shared [TtsPreferences]
 * interface, because mobile selects its voice through the OS (the same split as the
 * desktop-only UI-zoom living only on `DesktopThemePreferences`, invariant #45).
 * [DesktopTtsSpeaker] reads [voiceConfig] at speak time; the Settings UI observes
 * [voiceConfigFlow] and writes via [setVoiceConfig].
 */
class DesktopTtsPreferences(private val store: DesktopJsonStore) : TtsPreferences {

    private val state = MutableStateFlow(
        store.getString(KEY_ENABLED)?.toBooleanStrictOrNull() ?: false,
    )

    private val voiceState = MutableStateFlow(loadVoiceConfig())

    override fun isEnabled(): Boolean = state.value
    override fun enabledFlow(): Flow<Boolean> = state.asStateFlow()
    override fun setEnabled(enabled: Boolean) {
        if (state.value == enabled) return
        state.value = enabled
        store.putString(KEY_ENABLED, enabled.toString())
    }

    fun voiceConfig(): DesktopVoiceConfig = voiceState.value
    fun voiceConfigFlow(): Flow<DesktopVoiceConfig> = voiceState.asStateFlow()
    fun setVoiceConfig(config: DesktopVoiceConfig) {
        if (voiceState.value == config) return
        voiceState.value = config
        store.putString(KEY_VOICE_ENGINE, config.engine)
        store.putString(KEY_VOICE, config.voice)
        store.putString(KEY_VOICE_RATE, config.rate.toString())
    }

    private fun loadVoiceConfig() = DesktopVoiceConfig(
        engine = store.getString(KEY_VOICE_ENGINE).orEmpty(),
        voice = store.getString(KEY_VOICE).orEmpty(),
        rate = store.getString(KEY_VOICE_RATE)?.toIntOrNull() ?: 0,
    )

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_VOICE_ENGINE = "voice_engine"
        const val KEY_VOICE = "voice"
        const val KEY_VOICE_RATE = "voice_rate"
    }
}
