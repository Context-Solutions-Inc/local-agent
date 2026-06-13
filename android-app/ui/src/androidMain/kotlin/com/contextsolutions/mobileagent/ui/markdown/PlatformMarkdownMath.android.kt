package com.contextsolutions.mobileagent.ui.markdown

import android.text.Spannable
import android.text.method.ArrowKeyMovementMethod
import android.text.style.ClickableSpan
import android.util.TypedValue
import android.view.MotionEvent
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
 *
 * Links: a selectable TextView installs [ArrowKeyMovementMethod], which swallows
 * the touch events a `LinkMovementMethod` needs — so markdown links render styled
 * but never fire on tap. [LinkAwareMovementMethod] restores link taps (fired on
 * ACTION_UP) while keeping selection intact by extending the selection movement
 * method rather than replacing it. Applied AFTER [Markwon.setMarkdown] so it wins
 * over Markwon's own `MovementMethodPlugin`.
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
            // Re-assert after setMarkdown (Markwon installs LinkMovementMethod,
            // which would break selection) so taps AND selection both work.
            tv.movementMethod = LinkAwareMovementMethod
        },
    )
}

/**
 * Selection-friendly link movement. Extends [ArrowKeyMovementMethod] so a
 * selectable TextView keeps its touch-selection behavior, but intercepts a
 * tap that lands on a [ClickableSpan] and fires it on ACTION_UP. ACTION_DOWN
 * is not consumed, so long-press/drag still starts a selection.
 */
private object LinkAwareMovementMethod : ArrowKeyMovementMethod() {
    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val x = event.x.toInt() - widget.totalPaddingLeft + widget.scrollX
            val y = event.y.toInt() - widget.totalPaddingTop + widget.scrollY
            val layout = widget.layout
            if (layout != null) {
                val line = layout.getLineForVertical(y)
                // Reject taps in the empty area past a line's text so a tap to the
                // right of a link-terminated line doesn't spuriously fire it.
                if (x.toFloat() in layout.getLineLeft(line)..layout.getLineRight(line)) {
                    val off = layout.getOffsetForHorizontal(line, x.toFloat())
                    val links = buffer.getSpans(off, off, ClickableSpan::class.java)
                    if (links.isNotEmpty()) {
                        links[0].onClick(widget)
                        return true
                    }
                }
            }
        }
        return super.onTouchEvent(widget, buffer, event)
    }
}
