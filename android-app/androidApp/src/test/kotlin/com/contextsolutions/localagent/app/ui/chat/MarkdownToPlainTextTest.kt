package com.contextsolutions.localagent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the markdown/LaTeX stripping fed to text-to-speech: raw markers must
 * not be spoken, but the underlying words/figures must survive verbatim.
 */
class MarkdownToPlainTextTest {

    @Test
    fun strips_bold_and_italic_emphasis() {
        assertEquals(
            "Bold and italic and also bold underscore.",
            MarkdownToPlainText.strip("**Bold** and *italic* and also __bold underscore__."),
        )
    }

    @Test
    fun keeps_link_label_drops_url() {
        assertEquals(
            "See the docs for details.",
            MarkdownToPlainText.strip("See [the docs](https://example.com/x) for details."),
        )
    }

    @Test
    fun strips_inline_and_fenced_code() {
        assertEquals(
            "Run npm install then go.",
            MarkdownToPlainText.strip("Run `npm install` then go."),
        )
        assertEquals(
            "val x = 1",
            MarkdownToPlainText.strip("```kotlin\nval x = 1\n```"),
        )
    }

    @Test
    fun strips_latex_delimiters_keeping_inner_text() {
        assertEquals("x^2 + 1", MarkdownToPlainText.strip("\$x^2 + 1\$"))
        assertEquals("E = mc^2", MarkdownToPlainText.strip("\$\$E = mc^2\$\$"))
        assertEquals("a+b", MarkdownToPlainText.strip("\\(a+b\\)"))
    }

    @Test
    fun strips_headers_and_list_markers() {
        val input = "# Title\n\n- first\n- second\n1. one\n2. two"
        assertEquals("Title\n\nfirst\nsecond\none\ntwo", MarkdownToPlainText.strip(input))
    }

    @Test
    fun preserves_currency_and_snake_case() {
        // No markdown here — the stripper must not eat plain prose.
        assertEquals(
            "It cost \$5 and the var is my_value here.",
            MarkdownToPlainText.strip("It cost \$5 and the var is my_value here."),
        )
    }

    @Test
    fun plain_prose_is_unchanged() {
        val plain = "The capital of France is Paris."
        assertEquals(plain, MarkdownToPlainText.strip(plain))
    }
}
