package com.contextsolutions.mobileagent.app.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User's preferred theme mode. [System] (default) tracks `isSystemInDarkTheme()`
 * per MD3 guidance — never force a mode on users. [Light] / [Dark] override the
 * system setting for users who want the opposite of their system theme (e.g.
 * reading at night with a dark phone but a light app).
 */
enum class ThemeMode {
    System,
    Light,
    Dark;

    /** Next state in the cycle for the chat-screen toggle. */
    fun next(): ThemeMode = when (this) {
        System -> Light
        Light -> Dark
        Dark -> System
    }
}

/**
 * Persistent state for the user's theme-mode preference. Default `System`
 * so the app respects the OS dark-mode setting until the user explicitly
 * chooses otherwise (MD3 principle: "Don't override the system theme
 * without giving users a way out").
 *
 * Android impl ([SharedPreferencesThemePreferences]) lives in the same
 * file because this preference is UI-only — no need to expose it to the
 * KMP shared module.
 */
interface ThemePreferences {
    fun themeMode(): ThemeMode
    fun themeModeFlow(): Flow<ThemeMode>
    fun setThemeMode(mode: ThemeMode)
}

class SharedPreferencesThemePreferences(context: Context) : ThemePreferences {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_MODE, ThemeMode.System.name)!!) }
            .getOrDefault(ThemeMode.System),
    )

    override fun themeMode(): ThemeMode = state.value
    override fun themeModeFlow(): Flow<ThemeMode> = state.asStateFlow()
    override fun setThemeMode(mode: ThemeMode) {
        if (state.value == mode) return
        state.value = mode
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    private companion object {
        const val PREFS_NAME = "theme"
        const val KEY_MODE = "mode"
    }
}
