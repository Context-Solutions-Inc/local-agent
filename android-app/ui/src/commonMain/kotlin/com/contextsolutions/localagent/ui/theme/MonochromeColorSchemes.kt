package com.contextsolutions.localagent.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Shared monochrome Material 3 colour schemes: white surfaces + black trim in light,
 * near-black (#121212) surfaces + white trim in dark. Used by **desktop and iOS**
 * (Android keeps Material You / the green fallback — see the per-platform `Theme.kt`).
 * Invariant #46.
 *
 * These are plain `ColorScheme` literals (no platform dependency), so they live in
 * `:ui` commonMain and both shells reference the same values. `surfaceTint =
 * Transparent` disables M3 tonal-elevation overlays so surfaces stay flat black/white
 * (the `outline` provides separation instead of an elevation tint). The
 * `surfaceContainer*` roles MUST be defined — left unset they fall back to M3's purple
 * baseline, which tints dialogs/menus (e.g. `AlertDialog`).
 */
val LightMonochromeColorScheme: ColorScheme = lightColorScheme(
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

val DarkMonochromeColorScheme: ColorScheme = darkColorScheme(
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
