package com.contextsolutions.localagent.ui.markdown

/**
 * Shared markdown / LaTeX segment splitter (invariant #41). Used by the desktop and
 * iOS [PlatformMarkdownMath] actuals, which both render markdown runs via the mikepenz
 * Compose-Multiplatform renderer and each `$$…$$` math block as an image (JLaTeXMath on
 * desktop, a native SwiftMath bridge on iOS). Android renders via Markwon + ext-latex
 * and does not use this. Kept in commonMain so the two platforms stay in lockstep.
 */
internal sealed interface MdSegment {
    data class Markdown(val text: String) : MdSegment
    data class Math(val latex: String) : MdSegment
}

private val BLOCK_MATH = Regex("""\$\$([\s\S]+?)\$\$""")

/**
 * Split text already normalized by [LatexNormalizer] into alternating markdown /
 * `$$…$$` math segments. A run with no math blocks yields a single markdown segment.
 *
 * Each markdown run is `.trim()`med of the blank lines a `$$…$$` block leaves around
 * it (the math body is trimmed too). Without this the mikepenz renderer turns those
 * leading/trailing newlines into empty paragraphs — extra vertical space above/below
 * the rendered math image, and at the very top of an answer that opens with a blank
 * line (invariant #41 rendering fix).
 */
internal fun splitMarkdownAndMath(text: String): List<MdSegment> {
    val out = ArrayList<MdSegment>()
    var last = 0
    for (match in BLOCK_MATH.findAll(text)) {
        if (match.range.first > last) {
            addMarkdown(out, text.substring(last, match.range.first))
        }
        out.add(MdSegment.Math(match.groupValues[1].trim()))
        last = match.range.last + 1
    }
    if (last < text.length) addMarkdown(out, text.substring(last))
    if (out.isEmpty()) out.add(MdSegment.Markdown(text.trim()))
    return out
}

/** Add a markdown run with its surrounding blank lines trimmed; skip if it's all whitespace. */
private fun addMarkdown(out: MutableList<MdSegment>, raw: String) {
    val trimmed = raw.trim()
    if (trimmed.isNotEmpty()) out.add(MdSegment.Markdown(trimmed))
}
