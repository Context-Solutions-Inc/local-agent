package com.contextsolutions.mobileagent.desktop.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.contextsolutions.mobileagent.ui.theme.AppFontFamily
import com.contextsolutions.mobileagent.ui.theme.AppThemeScaffold
import com.contextsolutions.mobileagent.ui.theme.FontScale
import com.contextsolutions.mobileagent.ui.theme.ThemeMode

/**
 * Desktop counterpart to Android's `MobileAgentTheme`. Desktop has no Material You
 * dynamic colour, so it always uses the hand-tuned brand green schemes (copied
 * verbatim from `androidApp/.../app/ui/theme/Theme.kt` to keep the two platforms
 * visually consistent — these are plain Compose `*ColorScheme` literals with no
 * Android dependency).
 *
 * The fix in PR #59: desktop previously wrapped the UI in a bare `MaterialTheme {}`
 * that never read the theme preference, so the light/auto/dark selector did nothing.
 * `Main.kt` now collects `ThemeModeViewModel.mode` and passes it here, so toggling
 * recomposes the colour scheme live.
 *
 *  - [ThemeMode.System] follows `isSystemInDarkTheme()`
 *  - [ThemeMode.Light] / [ThemeMode.Dark] override the OS choice
 */
private val LightGreenColorScheme = lightColorScheme(
    primary = Color(0xFF386A20),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB7F397),
    onPrimaryContainer = Color(0xFF042100),
    secondary = Color(0xFF55624C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E7CB),
    onSecondaryContainer = Color(0xFF131F0D),
    tertiary = Color(0xFF386666),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBBEBEB),
    onTertiaryContainer = Color(0xFF002020),
    background = Color(0xFFF7FBEF),
    onBackground = Color(0xFF191D17),
    surface = Color(0xFFF7FBEF),
    onSurface = Color(0xFF191D17),
    surfaceVariant = Color(0xFFDEE5D4),
    onSurfaceVariant = Color(0xFF424940),
    outline = Color(0xFF72796F),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

private val DarkGreenColorScheme = darkColorScheme(
    primary = Color(0xFF9CD67D),
    onPrimary = Color(0xFF0F3900),
    primaryContainer = Color(0xFF1F5108),
    onPrimaryContainer = Color(0xFFB7F397),
    secondary = Color(0xFFBDCBB0),
    onSecondary = Color(0xFF283420),
    secondaryContainer = Color(0xFF3E4A36),
    onSecondaryContainer = Color(0xFFD9E7CB),
    tertiary = Color(0xFFA0CFCE),
    onTertiary = Color(0xFF003737),
    tertiaryContainer = Color(0xFF1F4E4E),
    onTertiaryContainer = Color(0xFFBBEBEB),
    background = Color(0xFF11140F),
    onBackground = Color(0xFFE2E3DA),
    surface = Color(0xFF11140F),
    onSurface = Color(0xFFE2E3DA),
    surfaceVariant = Color(0xFF424940),
    onSurfaceVariant = Color(0xFFC2C9BB),
    outline = Color(0xFF8C9387),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun MobileAgentDesktopTheme(
    themeMode: ThemeMode = ThemeMode.System,
    fontScale: Float = FontScale.DEFAULT,
    fontFamily: AppFontFamily = AppFontFamily.System,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val colorScheme = if (darkTheme) DarkGreenColorScheme else LightGreenColorScheme
    AppThemeScaffold(
        colorScheme = colorScheme,
        fontScale = fontScale,
        fontFamily = fontFamily,
        content = content,
    )
}
