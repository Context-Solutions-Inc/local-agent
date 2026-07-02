package com.contextsolutions.localagent.ui.markdown

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import com.contextsolutions.localagent.platform.LatexImage
import com.contextsolutions.localagent.platform.NativeLatexRenderer
import com.contextsolutions.localagent.ui.util.decodeImageBitmap

/**
 * iOS inline-aware markdown + LaTeX parsing (PR #46 follow-up). Unlike the block-only
 * desktop split (`splitMarkdownAndMath`), this keeps INLINE math (`$…$`, `\(…\)`) flowing
 * within its text line via Compose [InlineTextContent], so a sentence like "the value
 * $x^2$ here" reads on one line instead of stacking each symbol as its own image block.
 * DISPLAY math (`$$…$$`, `\[…\]`) stays a standalone image block. Math-free text is handed
 * to the mikepenz renderer verbatim so full markdown fidelity (lists, tables, code
 * fences, headings) is preserved wherever no inline math forces the lightweight path.
 */
internal sealed interface IosMdBlock {
    /** A run with no inline math — rendered by the full mikepenz markdown renderer. */
    data class Markdown(val text: String) : IosMdBlock

    /** A paragraph containing inline math — rendered as one flowing [Text] (see below). */
    data class InlineText(val raw: String) : IosMdBlock

    /** Display math (`$$…$$` / `\[…\]`) — rendered as a standalone image block. */
    data class DisplayMath(val latex: String) : IosMdBlock
}

// Display math: `$$…$$` or `\[…\]` (non-greedy, may span newlines).
private val DISPLAY_MATH = Regex("""\$\$([\s\S]+?)\$\$|\\\[([\s\S]+?)\\]""")

// Inline math: `\(…\)` or a single `$…$` pair (no `$$`, inner has no `$`/newline).
private val INLINE_MATH = Regex("""\\\(([\s\S]+?)\\\)|(?<!\$)\$(?!\$)([^$\n]+?)\$(?!\$)""")

// Inline markdown emphasis inside a text run: **bold** / __bold__ / *italic* / _italic_ /
// `code` / [text](url). Kept intentionally small — the common inline styles LLM math
// prose uses. Block structures on an inline-math line degrade to plain text (see class doc).
private val INLINE_MD = Regex(
    """\*\*([^*]+?)\*\*|__([^_]+?)__|(?<!\*)\*([^*\n]+?)\*(?!\*)|(?<!_)_([^_\n]+?)_(?!_)|`([^`]+?)`|\[([^\]]+?)\]\(([^)\s]+?)\)""",
)

private val LIST_MARKER = Regex("""(?m)^([ \t]*)[-*+][ \t]+""")
private val HEADING_MARKER = Regex("""(?m)^[ \t]*#{1,6}[ \t]+""")
private val PARAGRAPH_BREAK = Regex("""\n[ \t]*\n""")

private data class InlineSeg(val text: String?, val math: String?)

/** True if [text] contains at least one inline-math delimiter that looks like math. */
private fun hasInlineMath(text: String): Boolean =
    INLINE_MATH.findAll(text).any { m ->
        // group 1 = \(…\) (always math); group 2 = $…$ (only when it looks like math).
        m.groupValues[1].isNotEmpty() || LatexNormalizer.looksLikeMath(m.groupValues[2])
    }

/**
 * Split an assistant answer into display-math blocks, inline-math paragraphs, and pure
 * markdown runs. Math-free runs stay whole (one mikepenz call — no paragraph splitting, so
 * fenced code / multi-line lists keep rendering); a run WITH inline math is split into
 * paragraphs so only the paragraphs that actually carry math take the lightweight path.
 */
internal fun parseMathBlocks(text: String): List<IosMdBlock> {
    val blocks = ArrayList<IosMdBlock>()
    var last = 0
    for (m in DISPLAY_MATH.findAll(text)) {
        if (m.range.first > last) addTextRun(blocks, text.substring(last, m.range.first))
        val latex = m.groupValues[1].ifEmpty { m.groupValues[2] }.trim()
        blocks.add(IosMdBlock.DisplayMath(latex))
        last = m.range.last + 1
    }
    if (last < text.length) addTextRun(blocks, text.substring(last))
    if (blocks.isEmpty()) blocks.add(IosMdBlock.Markdown(text.trim()))
    return blocks
}

private fun addTextRun(blocks: MutableList<IosMdBlock>, run: String) {
    if (run.isEmpty()) return
    if (!hasInlineMath(run)) {
        // Trim the blank lines a display-math block leaves around the run so the
        // mikepenz renderer doesn't emit an empty leading/trailing paragraph
        // (extra vertical space around the image — matches the desktop split fix).
        val md = run.trim()
        if (md.isNotEmpty()) blocks.add(IosMdBlock.Markdown(md))
        return
    }
    // Split into paragraphs so a single math sentence doesn't drag a whole markdown run
    // onto the lightweight path.
    for (para in run.split(PARAGRAPH_BREAK)) {
        val p = para.trim()
        if (p.isEmpty()) continue
        if (hasInlineMath(p)) blocks.add(IosMdBlock.InlineText(p)) else blocks.add(IosMdBlock.Markdown(p))
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
 * image sized in `em` (so it tracks the surrounding font + font-scale) and vertically
 * centered on the text line. A missing bridge / unparseable formula / decode miss falls
 * back to the raw LaTeX shown as monospace code — never crashes.
 */
@Composable
internal fun InlineMathParagraph(
    raw: String,
    renderer: NativeLatexRenderer?,
    baseStyle: TextStyle,
    colorArgb: Int,
    fontSizePt: Float,
) {
    val fontPt = if (fontSizePt > 0f) fontSizePt else 16f
    val segs = remember(raw) { tokenizeInline(raw) }
    // Rasterize each math span once per (paragraph, colour, renderer).
    val rendered: List<LatexImage?> = remember(segs, colorArgb, renderer) {
        segs.map { seg -> seg.math?.let { renderer?.render(it, fontPt, colorArgb) } }
    }

    val inlineContent = HashMap<String, InlineTextContent>()
    val annotated = buildAnnotatedString {
        segs.forEachIndexed { index, seg ->
            if (seg.text != null) {
                appendInlineMarkdown(tidyBlockMarkers(seg.text))
                return@forEachIndexed
            }
            val latex = seg.math ?: return@forEachIndexed
            val img = rendered[index]
            val bitmap = img?.png?.let { decodeImageBitmap(it) }
            if (img != null && bitmap != null) {
                val id = "math_$index"
                inlineContent[id] = InlineTextContent(
                    Placeholder(
                        width = (img.widthPt / fontPt).em,
                        height = (img.heightPt / fontPt).em,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                    ),
                ) {
                    Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.fillMaxSize())
                }
                appendInlineContent(id, latex)
            } else {
                // Bridge absent / unparseable / decode miss — show the raw LaTeX inline.
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(latex) }
            }
        }
    }
    Text(text = annotated, style = baseStyle, inlineContent = inlineContent)
}

/** Append [text] with **bold** / *italic* / `code` / [link](url) styling; other markup literal. */
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInlineMarkdown(text: String) {
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
