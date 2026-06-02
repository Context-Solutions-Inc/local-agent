package com.contextsolutions.mobileagent.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class LatexNormalizerTest {

    @Test
    fun converts_single_dollar_math_to_double() {
        // The reported case: a whole-message inline expression.
        val input = "\$4k^3 + 8k^2 + 4k = 4k(k^2 + 2k + 1) = 4k(k+1)^2\$"
        val expected = "\$\$4k^3 + 8k^2 + 4k = 4k(k^2 + 2k + 1) = 4k(k+1)^2\$\$"
        assertEquals(expected, LatexNormalizer.normalize(input))
    }

    @Test
    fun converts_inline_math_inside_prose() {
        val input = "We factor \$x^2 - 1\$ as a difference of squares."
        assertEquals(
            "We factor \$\$x^2 - 1\$\$ as a difference of squares.",
            LatexNormalizer.normalize(input),
        )
    }

    @Test
    fun leaves_existing_double_dollar_untouched() {
        val input = "Block math:\n\$\$E = mc^2\$\$\ndone."
        assertEquals(input, LatexNormalizer.normalize(input))
    }

    @Test
    fun converts_latex_paren_and_bracket_forms() {
        assertEquals("\$\$a+b\$\$", LatexNormalizer.normalize("\\(a+b\\)"))
        assertEquals("\$\$x=1\$\$", LatexNormalizer.normalize("\\[x=1\\]"))
    }

    @Test
    fun leaves_prose_currency_alone() {
        val input = "It costs \$5 and the other is \$10 total."
        assertEquals(input, LatexNormalizer.normalize(input))
    }

    @Test
    fun leaves_plain_text_without_delimiters_unchanged() {
        val input = "Just a normal answer with no math."
        assertEquals(input, LatexNormalizer.normalize(input))
    }

    @Test
    fun converts_short_bare_variable() {
        assertEquals("the variable \$\$x\$\$ here", LatexNormalizer.normalize("the variable \$x\$ here"))
    }
}
