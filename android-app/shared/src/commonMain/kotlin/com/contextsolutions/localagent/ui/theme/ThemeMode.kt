package com.contextsolutions.localagent.ui.theme

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
 * User's preferred app font family. [System] keeps the platform default; the
 * others select a generic family resolved by each platform (no bundled font
 * assets) so the choice works on Android and desktop alike. Pure enum (no Compose
 * dependency) — the Compose `FontFamily` mapping lives in `:ui` (`AppTheme.kt`).
 */
enum class AppFontFamily {
    System,
    SansSerif,
    Serif,
    Monospace,
}

/**
 * Bounds for the user-controlled font-size multiplier (applied app-wide via a
 * `LocalDensity` `fontScale` override, so every `sp` text — including the Android
 * markdown TextView — scales). 1.0 = the platform default. The desktop default
 * density can render small on HiDPI monitors; this lets the user compensate.
 */
object FontScale {
    const val MIN = 0.8f
    const val MAX = 1.6f
    const val DEFAULT = 1.0f
}

/**
 * Bounds for the desktop-only **UI zoom** — a whole-UI scale factor applied by
 * multiplying `LocalDensity.density` (NOT just `fontScale`), so icons, padding,
 * forms AND text grow together. Driven by Ctrl/Cmd `+`/`-`/`0` at the desktop
 * window level; persisted in the desktop JSON prefs. The default desktop density
 * renders small on HiDPI monitors and the text-only [FontScale] slider can't grow
 * layout, so this is the lever for "everything is too small". Mobile does not use
 * it (its caller defaults the multiplier to 1.0).
 */
object UiZoom {
    const val MIN = 0.8f
    const val MAX = 2.0f
    const val DEFAULT = 1.0f
    const val STEP = 0.1f
}

/**
 * Persistent state for the user's appearance preferences — theme mode, font
 * family, and font size. Default `System` theme so the app respects the OS
 * dark-mode setting until the user explicitly chooses otherwise; default font
 * family `System` and scale `FontScale.DEFAULT`. Android impl backed by
 * `SharedPreferences` (`:androidApp`); desktop by a JSON store (`:shared`
 * desktopMain). Bound in each platform's Koin module.
 */
interface ThemePreferences {
    fun themeMode(): ThemeMode
    fun themeModeFlow(): Flow<ThemeMode>
    fun setThemeMode(mode: ThemeMode)

    fun fontScale(): Float
    fun fontScaleFlow(): Flow<Float>
    fun setFontScale(scale: Float)

    fun fontFamily(): AppFontFamily
    fun fontFamilyFlow(): Flow<AppFontFamily>
    fun setFontFamily(family: AppFontFamily)
}
