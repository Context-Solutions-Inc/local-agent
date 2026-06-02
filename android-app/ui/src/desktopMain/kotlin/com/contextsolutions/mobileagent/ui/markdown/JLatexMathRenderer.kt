package com.contextsolutions.mobileagent.ui.markdown

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Color
import java.awt.image.BufferedImage
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula

/**
 * Renders a LaTeX string to a Compose [ImageBitmap] via JLaTeXMath (Java2D),
 * for the desktop [PlatformMarkdownMath] actual (docs/DESKTOP_PORT_PLAN.md,
 * Phase 7). Offline, no WebView — the desktop analogue of Android Markwon's
 * `ext-latex` (which also wraps jlatexmath).
 *
 * Returns null on a malformed formula (unbalanced braces, unknown command) so
 * the caller can fall back to showing the raw LaTeX rather than crashing — the
 * graceful-degradation the streamed-from-a-2B-model input demands.
 *
 * @param fontSize point size for the rendered glyphs.
 * @param argbColor foreground colour (ARGB int from the Compose content colour).
 */
internal fun renderLatexToImageBitmap(latex: String, fontSize: Float, argbColor: Int): ImageBitmap? = try {
    val formula = TeXFormula(latex.trim())
    val icon = formula.createTeXIcon(TeXConstants.STYLE_DISPLAY, fontSize)
    icon.setForeground(Color(argbColor, true))
    val width = icon.iconWidth.coerceAtLeast(1)
    val height = icon.iconHeight.coerceAtLeast(1)
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    icon.paintIcon(null, graphics, 0, 0)
    graphics.dispose()
    image.toComposeImageBitmap()
} catch (t: Throwable) {
    null
}
