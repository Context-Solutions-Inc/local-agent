package com.contextsolutions.mobileagent.ui.markdown

/**
 * Normalizes the LaTeX delimiters an LLM emits into the single form both render
 * paths key on: `$$…$$` (docs/DESKTOP_PORT_PLAN.md, Phase 7 — invariant #41).
 *
 * The model writes inline math as `$…$`, display math as `$$…$$`, and sometimes
 * `\(…\)` / `\[…\]`. Android's Markwon `ext-latex` only matches `$$…$$`; the
 * desktop renderer splits on `$$…$$` to hand math blocks to JLaTeXMath. So both
 * actuals normalize first:
 *  - `\(…\)` and `\[…\]` → `$$…$$`
 *  - a matched single-`$…$` pair → `$$…$$`, but ONLY when the content looks like
 *    math, so prose currency ("it costs $5 and $10") is left untouched.
 *
 * Moved into `:ui` `commonMain` for the desktop port so the Android and desktop
 * `PlatformMarkdownMath` actuals share one normalizer. (`:androidApp` keeps its
 * own copy under `app.ui.chat` until the Chat screen moves to `:ui` in Phase 9,
 * at which point that copy is deleted in favour of this one.)
 */
object LatexNormalizer {

    // \(...\) and \[...\] — non-greedy, may span newlines.
    private val PAREN = Regex("""\\\(([\s\S]+?)\\\)""")
    private val BRACK = Regex("""\\\[([\s\S]+?)\\]""")

    // A single `$…$` pair: an opening `$` that is not part of `$$`, inner text
    // with no `$` or newline, and a closing `$` that is not part of `$$`.
    private val SINGLE_DOLLAR = Regex("""(?<!\$)\$(?!\$)([^$\n]+?)\$(?!\$)""")

    fun normalize(text: String): String {
        if (!text.contains('$') && !text.contains('\\')) return text
        var out = text
        out = PAREN.replace(out) { "$$${it.groupValues[1]}$$" }
        out = BRACK.replace(out) { "$$${it.groupValues[1]}$$" }
        out = SINGLE_DOLLAR.replace(out) { m ->
            val inner = m.groupValues[1]
            if (looksLikeMath(inner)) "$$$inner$$" else m.value
        }
        return out
    }

    /**
     * Heuristic to tell inline math from prose currency. Math if it carries a
     * LaTeX/structural token (`\ ^ _ { } =`), or a variable next to an operator
     * (`x+1`, `a < b`), or is a short bare symbol (`x`, `n`). Currency amounts
     * ("5", "1,000", "5 and ") have no letters+operators, so they stay literal.
     */
    private fun looksLikeMath(s: String): Boolean {
        if (s.any { it in "\\^_{}=" }) return true
        val hasLetter = s.any { it.isLetter() }
        val hasOperator = s.any { it in "+-*/<>()" }
        if (hasLetter && hasOperator) return true
        val trimmed = s.trim()
        return trimmed.length <= 3 && hasLetter && trimmed.all { it.isLetterOrDigit() }
    }
}
