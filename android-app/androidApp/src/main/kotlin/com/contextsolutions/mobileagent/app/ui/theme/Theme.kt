package com.contextsolutions.mobileagent.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.contextsolutions.mobileagent.ui.theme.AppFontFamily
import com.contextsolutions.mobileagent.ui.theme.AppThemeScaffold
import com.contextsolutions.mobileagent.ui.theme.FontScale
import com.contextsolutions.mobileagent.ui.theme.ThemeMode

/**
 * Brand light scheme — used on Android 11 and below (out of scope here:
 * minSdk 36) and whenever the user opts out of dynamic colour. Hand-tuned
 * to meet WCAG AA contrast for the `on*` role pairs.
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

/**
 * App theme. Material 3 with Material You (dynamic colour) on by default;
 * the hand-picked light/dark green schemes act as the brand fallback for
 * users who disable dynamic colour or run on devices that don't expose it.
 *
 * `themeMode` resolves to dark/light against the system setting:
 *  - [ThemeMode.System] (default) follows `isSystemInDarkTheme()`
 *  - [ThemeMode.Light] / [ThemeMode.Dark] override the system choice
 *
 * The `SideEffect` keeps status- and navigation-bar icon tint legible by
 * flipping the appearance bits to match the resolved theme. Window
 * background is left to edge-to-edge defaults (the legacy
 * `statusBarColor` / `navigationBarColor` setters are deprecated under
 * `enableEdgeToEdge()`).
 */
@Composable
fun MobileAgentTheme(
    themeMode: ThemeMode = ThemeMode.System,
    fontScale: Float = FontScale.DEFAULT,
    fontFamily: AppFontFamily = AppFontFamily.System,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkGreenColorScheme
        else -> LightGreenColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    AppThemeScaffold(
        colorScheme = colorScheme,
        fontScale = fontScale,
        fontFamily = fontFamily,
        content = content,
    )
}
