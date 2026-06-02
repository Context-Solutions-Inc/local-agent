package com.contextsolutions.mobileagent.ui.chat

/**
 * Strips Markdown + LaTeX formatting to plain prose for text-to-speech. The
 * chat renders model-composed answers with Markwon ([MarkdownMathText]); read
 * aloud, the raw markers ("asterisk asterisk", "dollar", "hash") are noise, so
 * we remove them before handing text to [ChatSpeaker]. Markwon is render-only
 * (no parse-to-text API), so this is a deliberately small regex pass — enough
 * for clean speech, not a full CommonMark parser. Citations are excluded
 * upstream (they live in a separate field, not in the spoken `.text`).
 */
object MarkdownToPlainText {

    fun strip(input: String): String {
        var s = input

        // Fenced code blocks: drop the ``` fences, keep the code text.
        s = Regex("```[a-zA-Z0-9]*\\n?").replace(s, "")
        // Inline code: `code` -> code
        s = Regex("`([^`]*)`").replace(s) { it.groupValues[1] }

        // LaTeX delimiters -> inner content. Block forms before inline.
        s = Regex("\\$\\$(.+?)\\$\\$", RegexOption.DOT_MATCHES_ALL).replace(s) { it.groupValues[1] }
        s = Regex("\\\\\\[(.+?)\\\\]", RegexOption.DOT_MATCHES_ALL).replace(s) { it.groupValues[1] }
        s = Regex("\\\\\\((.+?)\\\\\\)", RegexOption.DOT_MATCHES_ALL).replace(s) { it.groupValues[1] }
        s = Regex("\\$([^$\\n]+?)\\$").replace(s) { it.groupValues[1] }

        // Links / images: [label](url) -> label ; ![alt](url) -> alt
        s = Regex("!?\\[([^\\]]*)]\\([^)]*\\)").replace(s) { it.groupValues[1] }

        // Emphasis: **b** __b__ *i* _i_ ~~s~~ -> inner text.
        s = Regex("\\*\\*([^*]+)\\*\\*").replace(s) { it.groupValues[1] }
        s = Regex("__([^_]+)__").replace(s) { it.groupValues[1] }
        s = Regex("\\*([^*]+)\\*").replace(s) { it.groupValues[1] }
        // Underscore italics only when bounded by non-word chars, so
        // snake_case identifiers are left intact.
        s = Regex("(?<![A-Za-z0-9])_([^_]+)_(?![A-Za-z0-9])").replace(s) { it.groupValues[1] }
        s = Regex("~~([^~]+)~~").replace(s) { it.groupValues[1] }

        // Line-leading markers: headers, blockquotes, unordered + ordered lists.
        s = s.lines().joinToString("\n") { line ->
            line
                .replace(Regex("^\\s{0,3}#{1,6}\\s+"), "")
                .replace(Regex("^\\s{0,3}>\\s?"), "")
                .replace(Regex("^\\s*[-*+]\\s+"), "")
                .replace(Regex("^\\s*\\d+[.)]\\s+"), "")
        }

        // Drop horizontal-rule lines (---, ***, ___).
        s = s.lines().filterNot { it.matches(Regex("\\s*([-*_])\\1{2,}\\s*")) }.joinToString("\n")

        // Collapse 3+ blank lines to one paragraph break, then trim.
        s = Regex("\\n{3,}").replace(s, "\n\n")
        return s.trim()
    }
}
