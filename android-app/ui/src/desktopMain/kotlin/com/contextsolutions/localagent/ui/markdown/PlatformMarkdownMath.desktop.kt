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
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography

/**
 * Desktop [PlatformMarkdownMath] — the shared mikepenz Compose-Multiplatform markdown renderer
 * (no WebView) + the common [parseMathBlocks]/[InlineMathParagraph] path, with LaTeX rendered to
 * images via JLaTeXMath ([renderLatexToImageBitmap]). The only desktop-specific piece is the
 * rasterizer lambda (JVM/AWT JLaTeXMath); everything else is shared with Android + iOS
 * (invariant #41).
 *
 * Inline-aware: inline `$…$` / `\(…\)` flows within its text line, display `$$…$$` / `\[…\]`
 * renders as a standalone image, math-free runs go to the full mikepenz renderer. An unparseable
 * formula falls back to inline-code.
 *
 * mikepenz's `Markdown(...)` renders plain Compose `Text` that isn't selectable on its own; wrap
 * the column in a [SelectionContainer] so LLM answers can be selected + copied like the plain-text
 * path (shared with iOS + Android — Android's old native TextView gave selection for free).
 */
@Composable
actual fun PlatformMarkdownMath(text: String, modifier: Modifier) {
    val colorArgb = LocalContentColor.current.toArgb()
    // Pin body text to bodyMedium so a rendered answer matches the plain-text answers and the user
    // prompt bubbles (the library defaults body to the larger bodyLarge).
    val body = MaterialTheme.typography.bodyMedium
    val fontSizePt = body.fontSize.value
    val mdTypography = markdownTypography(
        text = body,
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
    )

    val blocks = remember(text) { parseMathBlocks(text) }

    SelectionContainer {
        Column(modifier) {
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
                            ) { latex, pt, argb ->
                                renderLatexToImageBitmap(latex, pt, argb)?.let {
                                    InlineMathImage(it, it.width / pt, it.height / pt)
                                }
                            }
                        is MdBlock.DisplayMath -> {
                            val bitmap = remember(block.latex, colorArgb) {
                                renderLatexToImageBitmap(block.latex, MATH_FONT_SIZE, colorArgb)
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
