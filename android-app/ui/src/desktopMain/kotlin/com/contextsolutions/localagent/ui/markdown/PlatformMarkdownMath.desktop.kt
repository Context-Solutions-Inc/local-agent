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
 * Desktop [PlatformMarkdownMath] — a Compose-Multiplatform markdown renderer
 * (no WebView) with LaTeX rendered to images via JLaTeXMath
 * ([renderLatexToImageBitmap]), the desktop counterpart of Android's Markwon +
 * `ext-latex` (invariant #41).
 *
 * Inline-aware ([parseMathBlocksDesktop], the JLaTeXMath mirror of iOS's
 * [IosMathMarkdown]): inline `$…$` / `\(…\)` flows within its text line via
 * [InlineMathParagraphDesktop] (so a phrase like "hypotenuse ($c$)" stays on one
 * line instead of stacking each symbol as its own block), display `$$…$$` / `\[…\]`
 * renders as a standalone image, and math-free runs go to the full mikepenz renderer.
 * An unparseable formula falls back to inline-code.
 */
@Composable
actual fun PlatformMarkdownMath(text: String, modifier: Modifier) {
    val colorArgb = LocalContentColor.current.toArgb()
    // Pin body text to bodyMedium so a rendered answer matches the plain-text answers
    // and the user prompt bubbles (the library defaults body to the larger bodyLarge).
    val body = MaterialTheme.typography.bodyMedium
    val fontSizePt = body.fontSize.value
    val mdTypography = markdownTypography(
        text = body,
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
    )

    val blocks = remember(text) { parseMathBlocksDesktop(text) }

    // mikepenz's `Markdown(...)` renders plain Compose `Text` that isn't
    // selectable on its own; wrap the column so LLM answers can be selected +
    // copied like the deterministic plain-text path (which uses its own
    // SelectionContainer in AssistantBubble). Android's markdown path gets
    // selection for free via its native TextView, so this is desktop-only.
    SelectionContainer {
        Column(modifier) {
            blocks.forEachIndexed { index, block ->
                key(index) {
                    when (block) {
                        is DesktopMdBlock.Markdown ->
                            Markdown(content = block.text, typography = mdTypography)
                        is DesktopMdBlock.InlineText ->
                            InlineMathParagraphDesktop(
                                raw = block.raw,
                                baseStyle = body,
                                colorArgb = colorArgb,
                                fontSizePt = fontSizePt,
                            )
                        is DesktopMdBlock.DisplayMath -> {
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
