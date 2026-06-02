package com.contextsolutions.mobileagent.ui.theme

import com.contextsolutions.mobileagent.platform.DesktopJsonStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop [ThemePreferences] — the theme-mode preference persisted in a small
 * JSON file (theme_prefs.json), the counterpart of Android's
 * `SharedPreferencesThemePreferences`. A [MutableStateFlow] seeded from disk
 * mirrors the Android `SharedPreferences`-backed flow.
 */
class DesktopThemePreferences(private val store: DesktopJsonStore) : ThemePreferences {

    private val state = MutableStateFlow(
        runCatching { ThemeMode.valueOf(store.getString(KEY_MODE) ?: ThemeMode.System.name) }
            .getOrDefault(ThemeMode.System),
    )

    override fun themeMode(): ThemeMode = state.value
    override fun themeModeFlow(): Flow<ThemeMode> = state.asStateFlow()
    override fun setThemeMode(mode: ThemeMode) {
        if (state.value == mode) return
        state.value = mode
        store.putString(KEY_MODE, mode.name)
    }

    private companion object {
        const val KEY_MODE = "mode"
    }
}
