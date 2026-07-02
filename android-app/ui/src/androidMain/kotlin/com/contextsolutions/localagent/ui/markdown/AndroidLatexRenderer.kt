package com.contextsolutions.localagent.ui.markdown

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * Renders a LaTeX string to a Compose [ImageBitmap] via `jlatexmath-android` (an Android-`Canvas`
 * fork of JLaTeXMath, offline, no WebView) — the Android counterpart of desktop's JVM/AWT
 * [renderLatexToImageBitmap] and iOS's SwiftMath bridge (invariant #41). This is the same LaTeX
 * engine Markwon's `ext-latex` wrapped, now used directly so the markdown path is pure Compose
 * (no `AndroidView`/`TextView` interop).
 *
 * `JLatexMathDrawable.textSize` is in **pixels** (unlike desktop JLaTeXMath's points), so callers
 * pass a density-resolved px size. Returns null on a malformed formula (unbalanced braces, unknown
 * command) so the caller can fall back to showing the raw LaTeX rather than crashing.
 *
 * @param textSizePx glyph size in pixels.
 * @param argbColor foreground colour (ARGB int from the Compose content colour).
 */
internal fun renderAndroidLatex(latex: String, textSizePx: Float, argbColor: Int): ImageBitmap? = try {
    val drawable = JLatexMathDrawable.builder(latex.trim())
        .textSize(textSizePx)
        .color(argbColor)
        .align(JLatexMathDrawable.ALIGN_LEFT)
        .build()
    val width = drawable.intrinsicWidth.coerceAtLeast(1)
    val height = drawable.intrinsicHeight.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, width, height)
    drawable.draw(canvas)
    bitmap.asImageBitmap()
} catch (t: Throwable) {
    null
}
