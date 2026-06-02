package com.contextsolutions.mobileagent.ui.theme

import kotlinx.coroutines.flow.Flow

/**
 * User's preferred theme mode. [System] (default) tracks the OS dark-mode
 * setting per MD3 guidance — never force a mode on users. [Light] / [Dark]
 * override the system setting for users who want the opposite of their system
 * theme (e.g. reading at night with a dark phone but a light app).
 *
 * Moved to shared `:ui` in Phase 9 (the Chat screen's theme toggle is shared).
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
 * Persistent state for the user's theme-mode preference. Default `System` so the
 * app respects the OS dark-mode setting until the user explicitly chooses
 * otherwise. Android impl backed by `SharedPreferences` (`:androidApp`); desktop
 * by a JSON store (`:shared` desktopMain). Bound in each platform's Koin module.
 */
interface ThemePreferences {
    fun themeMode(): ThemeMode
    fun themeModeFlow(): Flow<ThemeMode>
    fun setThemeMode(mode: ThemeMode)
}
