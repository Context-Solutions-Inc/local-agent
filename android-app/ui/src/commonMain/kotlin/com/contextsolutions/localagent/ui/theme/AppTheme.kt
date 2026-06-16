package com.contextsolutions.localagent.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density

/**
 * Maps the platform-agnostic [AppFontFamily] preference to a Compose
 * [FontFamily]. [AppFontFamily.System] → `null` (keep the M3 default). The
 * generic families resolve to platform fonts on both Android and desktop with no
 * bundled assets.
 */
fun AppFontFamily.toComposeFontFamily(): FontFamily? = when (this) {
    AppFontFamily.System -> null
    AppFontFamily.SansSerif -> FontFamily.SansSerif
    AppFontFamily.Serif -> FontFamily.Serif
    AppFontFamily.Monospace -> FontFamily.Monospace
}

private fun Typography.withFontFamily(family: FontFamily): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)

/**
 * The shared theme body used by both platform theme wrappers (Android's
 * `LocalAgentTheme`, desktop's `LocalAgentDesktopTheme`). Applies the user's
 * appearance preferences on top of a resolved [colorScheme]:
 *
 *  - **[fontScale]** multiplies the ambient `LocalDensity.fontScale`, so EVERY
 *    `sp` text scales uniformly app-wide — including the Android markdown
 *    TextView (it derives its px size from `LocalDensity.current`, invariant #41)
 *    and the whole desktop UI (whose default density renders small on HiDPI).
 *  - **[densityScale]** multiplies `LocalDensity.density`, so the WHOLE UI zooms
 *    uniformly — `dp` (icons, padding, forms) AND `sp` (text). This is the desktop
 *    Ctrl/Cmd `+`/`-` zoom (invariant: text-only [fontScale] can't grow layout).
 *    Defaults to `1f`, so Android is unaffected (mobile uses [fontScale] only).
 *  - **[fontFamily]** overrides the typography family (System keeps the M3
 *    default). Note: the Android markdown TextView keeps its own typeface, so the
 *    family applies to all Compose text but not that one native view — size still
 *    scales there.
 */
@Composable
fun AppThemeScaffold(
    colorScheme: ColorScheme,
    fontScale: Float,
    fontFamily: AppFontFamily,
    densityScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val typography = remember(fontFamily) {
        val base = Typography()
        fontFamily.toComposeFontFamily()?.let { base.withFontFamily(it) } ?: base
    }
    val base = LocalDensity.current
    val scaled = remember(base, fontScale, densityScale) {
        Density(base.density * densityScale, base.fontScale * fontScale)
    }
    CompositionLocalProvider(LocalDensity provides scaled) {
        MaterialTheme(colorScheme = colorScheme, typography = typography, content = content)
    }
}
