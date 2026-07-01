package com.contextsolutions.localagent.desktop.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.contextsolutions.localagent.ui.theme.AppFontFamily
import com.contextsolutions.localagent.ui.theme.AppThemeScaffold
import com.contextsolutions.localagent.ui.theme.DarkMonochromeColorScheme
import com.contextsolutions.localagent.ui.theme.FontScale
import com.contextsolutions.localagent.ui.theme.LightMonochromeColorScheme
import com.contextsolutions.localagent.ui.theme.ThemeMode

/**
 * Desktop counterpart to Android's `LocalAgentTheme`. Desktop has no Material You
 * dynamic colour, so it always uses these hand-tuned schemes — plain Compose
 * `*ColorScheme` literals with no Android dependency.
 *
 * **Monochrome (desktop-only):** white surfaces + black trim in light, near-black
 * (#121212) surfaces + white trim in dark. Deliberately diverges from Android,
 * which keeps Material You / the green fallback. `surfaceTint = Transparent`
 * disables M3 tonal-elevation overlays so surfaces stay flat black/white (the
 * `outline` provides separation instead of an elevation tint).
 *
 * The fix in PR #59: desktop previously wrapped the UI in a bare `MaterialTheme {}`
 * that never read the theme preference, so the light/auto/dark selector did nothing.
 * `Main.kt` now collects the theme preference and passes it here, so toggling
 * recomposes the colour scheme live.
 *
 *  - [ThemeMode.System] follows `isSystemInDarkTheme()`. NOTE: on **Linux** Skiko's
 *    OS-theme detection is unreliable (often reports light, no live update), so
 *    Auto may not track the desktop theme there — use Light/Dark explicitly.
 *    macOS/Windows detect correctly.
 *  - [ThemeMode.Light] / [ThemeMode.Dark] override the OS choice
 *
 * The monochrome `*ColorScheme` literals moved to `:ui` commonMain
 * ([LightMonochromeColorScheme]/[DarkMonochromeColorScheme]) so iOS shares them.
 */
@Composable
fun LocalAgentDesktopTheme(
    themeMode: ThemeMode = ThemeMode.System,
    fontScale: Float = FontScale.DEFAULT,
    fontFamily: AppFontFamily = AppFontFamily.System,
    densityScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val colorScheme = if (darkTheme) DarkMonochromeColorScheme else LightMonochromeColorScheme
    AppThemeScaffold(
        colorScheme = colorScheme,
        fontScale = fontScale,
        fontFamily = fontFamily,
        densityScale = densityScale,
        content = content,
    )
}
