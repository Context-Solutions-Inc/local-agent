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
 * (no WebView) with LaTeX blocks rendered to images via JLaTeXMath
 * ([renderLatexToImageBitmap]), the desktop counterpart of Android's Markwon +
 * `ext-latex` (invariant #41).
 *
 * The text is normalized to `$$…$$` math blocks ([LatexNormalizer]) and split on
 * them: markdown runs render via `Markdown(...)`, math blocks render as a
 * JLaTeXMath image (falling back to inline-code if the formula won't parse). A
 * former-inline `$…$` becomes its own stacked block on desktop — not pixel-
 * perfect inline, but correct + readable; the polished inline layout is a later
 * refinement (operator visual check).
 */
@Composable
actual fun PlatformMarkdownMath(text: String, modifier: Modifier) {
    val colorArgb = LocalContentColor.current.toArgb()
    val normalized = LatexNormalizer.normalize(text)
    // Pin body text to bodyMedium so a rendered answer matches the plain-text answers
    // and the user prompt bubbles (the library defaults body to the larger bodyLarge).
    val body = MaterialTheme.typography.bodyMedium
    val mdTypography = markdownTypography(
        text = body,
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
    )

    // Render math to bitmaps once per (text, colour) — outside composition slots.
    val segments = remember(normalized, colorArgb) {
        splitMarkdownAndMath(normalized).map { seg ->
            when (seg) {
                is MdSegment.Math -> seg to renderLatexToImageBitmap(seg.latex, MATH_FONT_SIZE, colorArgb)
                is MdSegment.Markdown -> seg to null
            }
        }
    }

    // mikepenz's `Markdown(...)` renders plain Compose `Text` that isn't
    // selectable on its own; wrap the column so LLM answers can be selected +
    // copied like the deterministic plain-text path (which uses its own
    // SelectionContainer in AssistantBubble). Android's markdown path gets
    // selection for free via its native TextView, so this is desktop-only.
    SelectionContainer {
        Column(modifier) {
            segments.forEachIndexed { index, (segment, bitmap) ->
                key(index) {
                    when (segment) {
                        is MdSegment.Markdown ->
                            if (segment.text.isNotBlank()) Markdown(content = segment.text, typography = mdTypography)
                        is MdSegment.Math ->
                            if (bitmap != null) {
                                Image(bitmap = bitmap, contentDescription = null)
                            } else {
                                // Unparseable formula — show it verbatim as inline code.
                                Markdown(content = "`${segment.latex}`", typography = mdTypography)
                            }
                    }
                }
            }
        }
    }
}

private const val MATH_FONT_SIZE = 18f

private sealed interface MdSegment {
    data class Markdown(val text: String) : MdSegment
    data class Math(val latex: String) : MdSegment
}

private val BLOCK_MATH = Regex("""\$\$([\s\S]+?)\$\$""")

/** Split normalized text into alternating markdown / `$$…$$` math segments. */
private fun splitMarkdownAndMath(text: String): List<MdSegment> {
    val out = ArrayList<MdSegment>()
    var last = 0
    for (match in BLOCK_MATH.findAll(text)) {
        if (match.range.first > last) {
            out.add(MdSegment.Markdown(text.substring(last, match.range.first)))
        }
        out.add(MdSegment.Math(match.groupValues[1].trim()))
        last = match.range.last + 1
    }
    if (last < text.length) out.add(MdSegment.Markdown(text.substring(last)))
    if (out.isEmpty()) out.add(MdSegment.Markdown(text))
    return out
}
