package com.contextsolutions.localagent.ui.markdown

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
    fun leaves_financial_currency_with_operators_alone() {
        // Inner "5 (a 10% gain) to " has both a letter and an operator '(',
        // which would trip the old heuristic — the digit-after-`$` guard keeps
        // it literal currency.
        val input = "Shares rose from \$5 (a 10% gain) to \$8 today."
        assertEquals(input, LatexNormalizer.normalize(input))
    }

    @Test
    fun leaves_currency_range_alone() {
        val input = "Guidance is \$100 to \$200 per share."
        assertEquals(input, LatexNormalizer.normalize(input))
    }

    @Test
    fun leaves_market_cap_currency_alone() {
        val input = "Apple's market cap is \$3.5T and revenue \$394B last year."
        assertEquals(input, LatexNormalizer.normalize(input))
    }

    @Test
    fun keeps_coefficient_led_math() {
        // Currency guard must NOT swallow math that opens with a coefficient —
        // the structural LaTeX-token check (`^`, `=`) wins.
        val input = "The polynomial \$4k^2 = 8\$ holds."
        assertEquals("The polynomial \$\$4k^2 = 8\$\$ holds.", LatexNormalizer.normalize(input))
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
