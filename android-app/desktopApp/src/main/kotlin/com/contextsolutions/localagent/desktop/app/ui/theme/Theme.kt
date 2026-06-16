package com.contextsolutions.localagent.desktop.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.contextsolutions.localagent.ui.theme.AppFontFamily
import com.contextsolutions.localagent.ui.theme.AppThemeScaffold
import com.contextsolutions.localagent.ui.theme.FontScale
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
 */
private val LightMonochromeColorScheme = lightColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E0E0),
    onPrimaryContainer = Color(0xFF000000),
    secondary = Color(0xFF000000),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6E6E6),
    onSecondaryContainer = Color(0xFF000000),
    tertiary = Color(0xFF000000),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE6E6E6),
    onTertiaryContainer = Color(0xFF000000),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFE0E0E0),
    onSurfaceVariant = Color(0xFF1A1A1A),
    // Surface container roles (Material3 1.2+). The monochrome scheme must define
    // these — left unset they fall back to M3's purple baseline, which tinted
    // dialogs/menus light purple (e.g. the AlertDialog container). Neutral light
    // greys keep dialogs consistent with the white job/chat surfaces.
    surfaceDim = Color(0xFFDADADA),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F5F5),
    surfaceContainer = Color(0xFFF0F0F0),
    surfaceContainerHigh = Color(0xFFEAEAEA),
    surfaceContainerHighest = Color(0xFFE4E4E4),
    outline = Color(0xFF000000),
    outlineVariant = Color(0xFFBDBDBD),
    surfaceTint = Color.Transparent,
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

private val DarkMonochromeColorScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF2A2A2A),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFFFFFFFF),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF2A2A2A),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFFFFFFFF),
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF2A2A2A),
    onTertiaryContainer = Color(0xFFFFFFFF),
    background = Color(0xFF121212),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFE0E0E0),
    // Neutral dark greys (vs M3's dark-purple baseline) so dialogs/menus match the
    // #121212 surface.
    surfaceDim = Color(0xFF121212),
    surfaceBright = Color(0xFF2E2E2E),
    surfaceContainerLowest = Color(0xFF0D0D0D),
    surfaceContainerLow = Color(0xFF1A1A1A),
    surfaceContainer = Color(0xFF1E1E1E),
    surfaceContainerHigh = Color(0xFF242424),
    surfaceContainerHighest = Color(0xFF2E2E2E),
    outline = Color(0xFFFFFFFF),
    outlineVariant = Color(0xFF3A3A3A),
    surfaceTint = Color.Transparent,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

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
