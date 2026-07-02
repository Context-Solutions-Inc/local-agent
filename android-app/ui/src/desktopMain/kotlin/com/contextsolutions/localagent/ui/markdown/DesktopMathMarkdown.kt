package com.contextsolutions.localagent.ui.markdown

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em

/**
 * Desktop inline-aware markdown + LaTeX parsing — the JLaTeXMath counterpart of iOS's
 * [IosMathMarkdown] (invariant #41). Replaces the earlier block-only `splitMarkdownAndMath`,
 * which normalized inline `$…$` to `$$…$$` FIRST and so stacked every inline symbol as its
 * own block (a phrase like "hypotenuse ($c$)" broke onto three lines).
 *
 * The RAW answer (NOT [LatexNormalizer]-normalized — normalizing erases the inline/display
 * distinction) is parsed into: pure-markdown runs (full mikepenz fidelity), inline-math
 * paragraphs (rendered as ONE flowing [Text] via Compose [InlineTextContent]), and
 * display-math blocks (`$$…$$` / `\[…\]`, a standalone JLaTeXMath image). Runs are trimmed of
 * the blank lines a math block leaves around them. A logic mirror of the iOS parser; a later
 * refactor could hoist the pure parsing to commonMain behind a shared math-renderer seam.
 */
internal sealed interface DesktopMdBlock {
    /** A run with no inline math — rendered by the full mikepenz markdown renderer. */
    data class Markdown(val text: String) : DesktopMdBlock

    /** A paragraph containing inline math — rendered as one flowing [Text]. */
    data class InlineText(val raw: String) : DesktopMdBlock

    /** Display math (`$$…$$` / `\[…\]`) — rendered as a standalone image block. */
    data class DisplayMath(val latex: String) : DesktopMdBlock
}

// Display math: `$$…$$` or `\[…\]` (non-greedy, may span newlines).
private val DISPLAY_MATH = Regex("""\$\$([\s\S]+?)\$\$|\\\[([\s\S]+?)\\]""")

// Inline math: `\(…\)` or a single `$…$` pair (no `$$`, inner has no `$`/newline).
private val INLINE_MATH = Regex("""\\\(([\s\S]+?)\\\)|(?<!\$)\$(?!\$)([^$\n]+?)\$(?!\$)""")

// Inline markdown emphasis inside a text run: **bold** / __bold__ / *italic* / _italic_ /
// `code` / [text](url) — the common inline styles LLM math prose uses.
private val INLINE_MD = Regex(
    """\*\*([^*]+?)\*\*|__([^_]+?)__|(?<!\*)\*([^*\n]+?)\*(?!\*)|(?<!_)_([^_\n]+?)_(?!_)|`([^`]+?)`|\[([^\]]+?)\]\(([^)\s]+?)\)""",
)

private val LIST_MARKER = Regex("""(?m)^([ \t]*)[-*+][ \t]+""")
private val HEADING_MARKER = Regex("""(?m)^[ \t]*#{1,6}[ \t]+""")
private val PARAGRAPH_BREAK = Regex("""\n[ \t]*\n""")

private data class InlineSeg(val text: String?, val math: String?)

/** True if [text] has at least one inline-math delimiter that looks like math (not currency). */
private fun hasInlineMath(text: String): Boolean =
    INLINE_MATH.findAll(text).any { m ->
        m.groupValues[1].isNotEmpty() || LatexNormalizer.looksLikeMath(m.groupValues[2])
    }

/** Split an answer into display-math blocks, inline-math paragraphs, and pure markdown runs. */
internal fun parseMathBlocksDesktop(text: String): List<DesktopMdBlock> {
    val blocks = ArrayList<DesktopMdBlock>()
    var last = 0
    for (m in DISPLAY_MATH.findAll(text)) {
        if (m.range.first > last) addTextRun(blocks, text.substring(last, m.range.first))
        val latex = m.groupValues[1].ifEmpty { m.groupValues[2] }.trim()
        blocks.add(DesktopMdBlock.DisplayMath(latex))
        last = m.range.last + 1
    }
    if (last < text.length) addTextRun(blocks, text.substring(last))
    if (blocks.isEmpty()) blocks.add(DesktopMdBlock.Markdown(text.trim()))
    return blocks
}

private fun addTextRun(blocks: MutableList<DesktopMdBlock>, run: String) {
    if (run.isEmpty()) return
    if (!hasInlineMath(run)) {
        // Trim the blank lines a display-math block leaves around the run so mikepenz
        // doesn't emit an empty leading/trailing paragraph (extra space around the image).
        val md = run.trim()
        if (md.isNotEmpty()) blocks.add(DesktopMdBlock.Markdown(md))
        return
    }
    // Split into paragraphs so a single math sentence doesn't drag a whole markdown run
    // onto the lightweight path.
    for (para in run.split(PARAGRAPH_BREAK)) {
        val p = para.trim()
        if (p.isEmpty()) continue
        if (hasInlineMath(p)) blocks.add(DesktopMdBlock.InlineText(p)) else blocks.add(DesktopMdBlock.Markdown(p))
    }
}

private fun tokenizeInline(text: String): List<InlineSeg> {
    val out = ArrayList<InlineSeg>()
    var last = 0
    for (m in INLINE_MATH.findAll(text)) {
        val paren = m.groupValues[1]
        val dollar = m.groupValues[2]
        val isMath = paren.isNotEmpty() || LatexNormalizer.looksLikeMath(dollar)
        if (!isMath) continue // a `$…$` that's really currency — leave it in the text run.
        if (m.range.first > last) out.add(InlineSeg(text = text.substring(last, m.range.first), math = null))
        out.add(InlineSeg(text = null, math = (paren.ifEmpty { dollar }).trim()))
        last = m.range.last + 1
    }
    if (last < text.length) out.add(InlineSeg(text = text.substring(last), math = null))
    return out
}

/** Strip list/heading markers to lightweight equivalents (bullets kept, `#` dropped). */
private fun tidyBlockMarkers(text: String): String =
    HEADING_MARKER.replace(LIST_MARKER.replace(text) { "${it.groupValues[1]}•  " }, "")

/**
 * Render an inline-math paragraph as ONE flowing [Text]: text runs get lightweight inline
 * markdown (bold/italic/code/links), each inline math span becomes an [InlineTextContent]
 * JLaTeXMath image sized in `em` (tracks the surrounding font) and vertically centered on the
 * line. A missing/unparseable formula falls back to the raw LaTeX as monospace — never crashes.
 */
@Composable
internal fun InlineMathParagraphDesktop(
    raw: String,
    baseStyle: TextStyle,
    colorArgb: Int,
    fontSizePt: Float,
) {
    val fontPt = if (fontSizePt > 0f) fontSizePt else 16f
    val segs = remember(raw) { tokenizeInline(raw) }
    // Rasterize each math span once per (paragraph, colour, size).
    val rendered = remember(segs, colorArgb, fontPt) {
        segs.map { seg -> seg.math?.let { renderLatexToImageBitmap(it, fontPt, colorArgb) } }
    }

    val inlineContent = HashMap<String, InlineTextContent>()
    val annotated = buildAnnotatedString {
        segs.forEachIndexed { index, seg ->
            if (seg.text != null) {
                appendInlineMarkdown(tidyBlockMarkers(seg.text))
                return@forEachIndexed
            }
            val latex = seg.math ?: return@forEachIndexed
            val bitmap = rendered[index]
            if (bitmap != null) {
                val id = "math_$index"
                inlineContent[id] = InlineTextContent(
                    Placeholder(
                        width = (bitmap.width / fontPt).em,
                        height = (bitmap.height / fontPt).em,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                    ),
                ) {
                    Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.fillMaxSize())
                }
                appendInlineContent(id, latex)
            } else {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(latex) }
            }
        }
    }
    Text(text = annotated, style = baseStyle, inlineContent = inlineContent)
}

/** Append [text] with **bold** / *italic* / `code` / [link](url) styling; other markup literal. */
private fun AnnotatedString.Builder.appendInlineMarkdown(text: String) {
    var last = 0
    for (m in INLINE_MD.findAll(text)) {
        if (m.range.first > last) append(text.substring(last, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[1]) }
            g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[2]) }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[3]) }
            g[4].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[4]) }
            g[5].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(g[5]) }
            g[6].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(g[6]) }
        }
        last = m.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}
