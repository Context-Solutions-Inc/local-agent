package com.contextsolutions.mobileagent.app.ui.chat

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
 * Renders an assistant reply as markdown + LaTeX math (PR #50).
 *
 * The model emits markdown (bold, lists, code) and LaTeX (`$…$` inline,
 * `$$…$$` block); both leaked as raw text under the old plain-[Text] bubble.
 * Markwon parses the markdown into a [TextView]; its `ext-latex` plugin draws
 * equations via jlatexmath on the native canvas (offline, no WebView).
 *
 * Used only for FINAL assistant turns flagged `renderMarkdown = true`
 * ([UiMessage.Assistant.renderMarkdown]). Deterministic weather/finance cards
 * (flagged false) and the still-streaming partial keep the plain [Text] path —
 * re-parsing markdown on every token would jank, and a half-emitted `$` would
 * render as broken math.
 *
 * Hosted in an [AndroidView]; `setTextIsSelectable(true)` gives the TextView
 * its own copy/select toolbar (Compose's `SelectionContainer` doesn't reach
 * into an embedded View, so it's dropped at the call site for this path).
 */
@Composable
fun MarkdownMathText(text: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Match the raw-text path's color exactly: the plain `Text(bodyMedium)`
    // has an unspecified color, so it resolves to LocalContentColor (onSurface —
    // white in dark mode, black in light). Use the same here rather than the
    // muted onSurfaceVariant.
    val textColor = LocalContentColor.current.toArgb()

    // Mirror the plain bubble's `bodyMedium` metrics so markdown text matches
    // the raw-text path exactly: same size, line height, and letter spacing.
    // The app uses the default Material3 typography (Theme.kt sets no custom
    // `typography`), and the platform default font matches Compose's default
    // FontFamily — so size/line-height/tracking + includeFontPadding=false
    // (Compose's default; TextView's is true) are all that differ.
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

    // Rebuilt only when the theme-derived color/size change (e.g. dark-mode
    // toggle). JLatexMathPlugin needs MarkwonInlineParserPlugin registered
    // first for inline `$…$`; `inlinesEnabled(true)` turns it on (block
    // `$$…$$` is on by default). SoftBreakAddsNewLinePlugin preserves the
    // model's single-newline line breaks (CommonMark would collapse them).
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
                // Compose Text defaults to no font padding; match it so the
                // first/last line and vertical rhythm line up with the raw path.
                includeFontPadding = false
            }
        },
        update = { tv ->
            tv.setTextColor(textColor)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
            tv.letterSpacing = letterSpacingEm
            if (lineHeightPx > 0f) tv.setLineHeight(lineHeightPx.roundToInt())
            // ext-latex only recognizes `$$…$$`; rewrite the model's `$…$` /
            // `\(…\)` / `\[…\]` to that form first (LatexNormalizer).
            markwon.setMarkdown(tv, LatexNormalizer.normalize(text))
        },
    )
}
