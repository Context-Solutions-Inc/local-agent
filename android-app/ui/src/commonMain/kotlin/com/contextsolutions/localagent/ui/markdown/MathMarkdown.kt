package com.contextsolutions.localagent.ui.markdown

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em

/**
 * Shared inline-aware markdown + LaTeX parsing (invariant #41). One implementation for every
 * platform — Android, desktop, and iOS all call [parseMathBlocks] + [InlineMathParagraph]; the
 * only per-platform seam is the LaTeX-to-image rasterizer (JLaTeXMath on the JVM, SwiftMath on
 * iOS, jlatexmath-android on Android), passed into [InlineMathParagraph] as a lambda.
 *
 * This replaces the earlier mirror-duplicated `DesktopMathMarkdown`/`IosMathMarkdown` parsers.
 * Unlike a block-only split that normalized inline `$…$` to `$$…$$` FIRST (stacking every inline
 * symbol as its own block — a phrase like "hypotenuse ($c$)" broke onto three lines), this keeps
 * INLINE math (`$…$`, `\(…\)`) flowing within its text line via Compose [InlineTextContent] and
 * only DISPLAY math (`$$…$$`, `\[…\]`) becomes a standalone image block. Math-free runs go to the
 * full mikepenz renderer verbatim so markdown fidelity (lists, tables, code fences, headings) is
 * preserved wherever no inline math forces the lightweight path.
 */
internal sealed interface MdBlock {
    /** A run with no inline math — rendered by the full mikepenz markdown renderer. */
    data class Markdown(val text: String) : MdBlock

    /** A paragraph containing inline math — rendered as one flowing [Text] via [InlineMathParagraph]. */
    data class InlineText(val raw: String) : MdBlock

    /** Display math (`$$…$$` / `\[…\]`) — rendered as a standalone image block. */
    data class DisplayMath(val latex: String) : MdBlock
}

// Display math: `$$…$$` or `\[…\]` (non-greedy, may span newlines).
private val DISPLAY_MATH = Regex("""\$\$([\s\S]+?)\$\$|\\\[([\s\S]+?)\\]""")

// Inline math: `\(…\)` or a single `$…$` pair (no `$$`, inner has no `$`/newline).
private val INLINE_MATH = Regex("""\\\(([\s\S]+?)\\\)|(?<!\$)\$(?!\$)([^$\n]+?)\$(?!\$)""")

// Inline markdown emphasis inside a text run: **bold** / __bold__ / *italic* / _italic_ /
// `code` / [text](url) — the common inline styles LLM math prose uses. Block structures on an
// inline-math line degrade to plain text (see class doc).
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
        // group 1 = \(…\) (always math); group 2 = $…$ (only when it looks like math).
        m.groupValues[1].isNotEmpty() || LatexNormalizer.looksLikeMath(m.groupValues[2])
    }

/**
 * Split an assistant answer into display-math blocks, inline-math paragraphs, and pure markdown
 * runs. Math-free runs stay whole (one mikepenz call — no paragraph splitting, so fenced code /
 * multi-line lists keep rendering); a run WITH inline math is split into paragraphs so only the
 * paragraphs that actually carry math take the lightweight path.
 */
internal fun parseMathBlocks(text: String): List<MdBlock> {
    val blocks = ArrayList<MdBlock>()
    var last = 0
    for (m in DISPLAY_MATH.findAll(text)) {
        if (m.range.first > last) addTextRun(blocks, text.substring(last, m.range.first))
        val latex = m.groupValues[1].ifEmpty { m.groupValues[2] }.trim()
        blocks.add(MdBlock.DisplayMath(latex))
        last = m.range.last + 1
    }
    if (last < text.length) addTextRun(blocks, text.substring(last))
    if (blocks.isEmpty()) blocks.add(MdBlock.Markdown(text.trim()))
    return blocks
}

private fun addTextRun(blocks: MutableList<MdBlock>, run: String) {
    if (run.isEmpty()) return
    if (!hasInlineMath(run)) {
        // Trim the blank lines a display-math block leaves around the run so mikepenz doesn't
        // emit an empty leading/trailing paragraph (extra vertical space around the image).
        val md = run.trim()
        if (md.isNotEmpty()) blocks.add(MdBlock.Markdown(md))
        return
    }
    // Split into paragraphs so a single math sentence doesn't drag a whole markdown run onto the
    // lightweight path.
    for (para in run.split(PARAGRAPH_BREAK)) {
        val p = para.trim()
        if (p.isEmpty()) continue
        if (hasInlineMath(p)) blocks.add(MdBlock.InlineText(p)) else blocks.add(MdBlock.Markdown(p))
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
 * A rasterized inline-math glyph: the [bitmap] plus its size in `em` (so it tracks the surrounding
 * font + font-scale). Each platform's rasterizer lambda computes [widthEm]/[heightEm] in its own
 * units (desktop/Android from bitmap pixels, iOS from the bridge's reported points) so the shared
 * [InlineMathParagraph] never has to know how the image was produced.
 */
internal data class InlineMathImage(val bitmap: ImageBitmap, val widthEm: Float, val heightEm: Float)

/**
 * Render an inline-math paragraph as ONE flowing [Text]: text runs get lightweight inline markdown
 * (bold/italic/code/links), each inline math span becomes an [InlineTextContent] image sized in
 * `em` and vertically centered on the line. [rasterize] turns a LaTeX string into an
 * [InlineMathImage]; a null return (missing bridge / unparseable formula / decode miss) falls back
 * to the raw LaTeX as monospace — never crashes.
 */
@Composable
internal fun InlineMathParagraph(
    raw: String,
    baseStyle: TextStyle,
    colorArgb: Int,
    fontSizePt: Float,
    rasterize: (latex: String, fontSizePt: Float, colorArgb: Int) -> InlineMathImage?,
) {
    val fontPt = if (fontSizePt > 0f) fontSizePt else 16f
    val segs = remember(raw) { tokenizeInline(raw) }
    // Rasterize each math span once per (paragraph, colour, size). rasterize is a stable per-platform
    // pure function, so it's intentionally not a remember key.
    val rendered = remember(segs, colorArgb, fontPt) {
        segs.map { seg -> seg.math?.let { rasterize(it, fontPt, colorArgb) } }
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
            if (img != null) {
                val id = "math_$index"
                inlineContent[id] = InlineTextContent(
                    Placeholder(
                        width = img.widthEm.em,
                        height = img.heightEm.em,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                    ),
                ) {
                    Image(bitmap = img.bitmap, contentDescription = null, modifier = Modifier.fillMaxSize())
                }
                appendInlineContent(id, latex)
            } else {
                // Unparseable / missing rasterizer — show the raw LaTeX inline as monospace.
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
