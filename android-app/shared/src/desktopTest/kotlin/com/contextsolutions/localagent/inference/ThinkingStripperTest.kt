package com.contextsolutions.localagent.inference

import kotlin.test.Test
import kotlin.test.assertEquals

class ThinkingStripperTest {

    /** Feed [text] through a fresh stripper in [chunk]-sized pieces (simulates streaming). */
    private fun strip(text: String, chunk: Int): String {
        val s = ThinkingStripper()
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val end = minOf(i + chunk, text.length)
            out.append(s.push(text.substring(i, end)))
            i = end
        }
        out.append(s.finish())
        return out.toString()
    }

    /** Assert the strip result is stable across a range of chunk sizes (boundary-split markers). */
    private fun assertStrips(input: String, expected: String) {
        for (c in intArrayOf(1, 2, 3, 5, 9, 17, 1000)) {
            assertEquals(expected, strip(input, c), "chunk=$c")
        }
    }

    @Test
    fun plain_text_passes_through() = assertStrips("The capital of France is Paris.", "The capital of France is Paris.")

    @Test
    fun strips_a_thought_channel_keeping_the_answer() =
        assertStrips("<|channel>thought\nlet me work it out...<channel|>The capital is Paris.", "The capital is Paris.")

    @Test
    fun trims_the_newline_left_between_channel_and_answer() =
        assertStrips("<|channel>thought\nreasoning<channel|>\nParis.", "Paris.")

    @Test
    fun keeps_text_before_and_after_a_channel() =
        assertStrips("Sure.<|channel>thought\nx<channel|> Final answer.", "Sure. Final answer.")

    @Test
    fun strips_multiple_channels() =
        assertStrips("<|channel>thought\na<channel|>One.<|channel>thought\nb<channel|> Two.", "One. Two.")

    @Test
    fun drops_the_bare_think_primer_token() =
        assertStrips("<|think|>The answer is 42.", "The answer is 42.")

    @Test
    fun leading_whitespace_is_trimmed() = assertStrips("   \n  Hello.", "Hello.")

    @Test
    fun unterminated_channel_emits_nothing() =
        assertStrips("<|channel>thought\nreasoning that never closes", "")
}
