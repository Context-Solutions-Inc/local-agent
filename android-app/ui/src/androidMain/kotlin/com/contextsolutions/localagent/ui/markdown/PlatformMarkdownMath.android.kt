package com.contextsolutions.localagent.ui.markdown

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography

/**
 * Android [PlatformMarkdownMath] — the shared mikepenz Compose-Multiplatform markdown renderer
 * (no WebView) + the common [parseMathBlocks]/[InlineMathParagraph] path, with LaTeX rendered to
 * images via `jlatexmath-android` ([renderAndroidLatex]). Identical structure to the desktop + iOS
 * actuals; the only Android-specific piece is the rasterizer lambda (invariant #41).
 *
 * The chat-render parity PR replaced the previous Markwon + `ext-latex` `AndroidView`/`TextView` renderer with
 * this pure-Compose path so every platform shares one markdown renderer (no `AndroidView` interop).
 * Selection now comes from the shared [SelectionContainer] (the native TextView gave it for free);
 * markdown links are handled by mikepenz's own `LocalUriHandler` wiring.
 */
@Composable
actual fun PlatformMarkdownMath(text: String, modifier: Modifier) {
    val colorArgb = LocalContentColor.current.toArgb()
    // Pin body text to bodyMedium so a rendered answer matches the plain-text answers and the user
    // prompt bubbles (the library defaults body to the larger bodyLarge).
    val body = MaterialTheme.typography.bodyMedium
    val fontSizePt = body.fontSize.value
    val density = LocalDensity.current
    // jlatexmath-android sizes in PIXELS, so resolve the sp body size (and a slightly larger
    // display-math size) against the current density. Tracks font-scale like the desktop path.
    val inlinePx = with(density) { body.fontSize.toPx() }
    val displayPx = with(density) { MATH_FONT_SIZE.sp.toPx() }
    val mdTypography = markdownTypography(
        text = body,
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
    )

    val blocks = remember(text) { parseMathBlocks(text) }

    SelectionContainer(modifier) {
        Column {
            blocks.forEachIndexed { index, block ->
                key(index) {
                    when (block) {
                        is MdBlock.Markdown ->
                            Markdown(content = block.text, typography = mdTypography)
                        is MdBlock.InlineText ->
                            InlineMathParagraph(
                                raw = block.raw,
                                baseStyle = body,
                                colorArgb = colorArgb,
                                fontSizePt = fontSizePt,
                            ) { latex, _, argb ->
                                renderAndroidLatex(latex, inlinePx, argb)?.let {
                                    InlineMathImage(it, it.width / inlinePx, it.height / inlinePx)
                                }
                            }
                        is MdBlock.DisplayMath -> {
                            val bitmap = remember(block.latex, colorArgb, displayPx) {
                                renderAndroidLatex(block.latex, displayPx, colorArgb)
                            }
                            if (bitmap != null) {
                                Image(bitmap = bitmap, contentDescription = null)
                            } else {
                                // Unparseable formula — show it verbatim as inline code.
                                Markdown(content = "`${block.latex}`", typography = mdTypography)
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val MATH_FONT_SIZE = 18f
