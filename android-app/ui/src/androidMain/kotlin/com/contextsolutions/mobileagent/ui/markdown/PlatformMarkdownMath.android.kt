package com.contextsolutions.mobileagent.ui.markdown

import android.util.TypedValue
import android.widget.TextView
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import kotlin.math.roundToInt

/**
 * Android [PlatformMarkdownMath] — Markwon + `ext-latex` (jlatexmath, native
 * canvas, no WebView), mirroring `:androidApp`'s `MarkdownMathText` (invariant
 * #41). Hosted in an [AndroidView] TextView whose metrics mirror `bodyMedium`
 * (size, line-height, letter-spacing, `includeFontPadding=false`) so markdown
 * text lines up with the plain-text path; `setTextIsSelectable(true)` gives the
 * TextView its own copy/select toolbar.
 */
@Composable
actual fun PlatformMarkdownMath(text: String, modifier: Modifier) {
    val context = LocalContext.current
    val textColor = LocalContentColor.current.toArgb()

    val style = MaterialTheme.typography.bodyMedium
    val density = LocalDensity.current
    val textSizePx = with(density) { style.fontSize.toPx() }
    val lineHeightPx = with(density) {
        if (style.lineHeight.isSpecified) style.lineHeight.toPx() else 0f
    }
    val letterSpacingEm = with(density) {
        if (style.letterSpacing.isSpecified && style.fontSize.isSpecified) {
            style.letterSpacing.toPx() / style.fontSize.toPx()
        } else {
            0f
        }
    }

    val markwon = remember(textColor, textSizePx) {
        Markwon.builder(context)
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(SoftBreakAddsNewLinePlugin.create())
            .usePlugin(
                JLatexMathPlugin.create(textSizePx) { builder ->
                    builder.inlinesEnabled(true)
                },
            )
            .build()
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                setTextIsSelectable(true)
                includeFontPadding = false
            }
        },
        update = { tv ->
            tv.setTextColor(textColor)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
            tv.letterSpacing = letterSpacingEm
            if (lineHeightPx > 0f) tv.setLineHeight(lineHeightPx.roundToInt())
            // ext-latex only recognizes `$$…$$`; normalize the model's `$…$` /
            // `\(…\)` / `\[…\]` first (shared LatexNormalizer).
            markwon.setMarkdown(tv, LatexNormalizer.normalize(text))
        },
    )
}
