package com.contextsolutions.mobileagent.app.ui.chat

/**
 * Normalizes the LaTeX delimiters an LLM emits into the ONLY form Markwon's
 * `ext-latex` (jlatexmath) recognizes: `$$…$$` (PR #50 follow-up).
 *
 * The model writes inline math as `$…$` and display math as `$$…$$`, and
 * sometimes `\(…\)` / `\[…\]`. But ext-latex 4.6.2's inline processor regex is
 * `(\${2})([\s\S]+?)\1` — it matches `$$…$$` ONLY. Single-`$` spans (the common
 * case, e.g. `$4k^3 + … = 4k(k+1)^2$`) match nothing and render as raw text.
 *
 * So we rewrite to `$$…$$`:
 *  - `\(…\)` and `\[…\]` → `$$…$$`
 *  - a matched single-`$…$` pair → `$$…$$`, but ONLY when the content looks
 *    like math, so prose currency ("it costs $5 and $10") is left untouched.
 *
 * Existing `$$…$$` is left as-is. Applied only on the markdown render path
 * (`renderMarkdown = true`); deterministic cards never reach here.
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
