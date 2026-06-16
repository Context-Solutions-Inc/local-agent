package com.contextsolutions.localagent.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Asserts the post-processing regex that scrubs Gemma's "appended trailing
 * digit on a minute" rendering glitch. Whatever the model does to a
 * correctly-shaped tool result on its way to the screen, the output should
 * end up as a canonical `H:MM PERIOD`.
 *
 * Scoped per AgentLoop.MINUTE_GLITCH_REGEX — anchored to AM/PM so it
 * never touches HH:MM:SS times or arbitrary numeric strings.
 */
class MinuteGlitchRegexTest {

    private fun scrub(input: String): String =
        AgentLoop.MINUTE_GLITCH_REGEX.replace(input) { m ->
            "${m.groupValues[1]}:${m.groupValues[2]} ${m.groupValues[3]}"
        }

    @Test
    fun `trims trailing digit on minute`() {
        assertEquals("7:30 AM", scrub("7:300 AM"))
        assertEquals("3:55 PM", scrub("3:555 PM"))
        assertEquals("12:00 PM", scrub("12:0000 PM"))
    }

    @Test
    fun `leaves correctly formatted times untouched`() {
        assertEquals("7:30 AM", scrub("7:30 AM"))
        assertEquals("12:00 PM", scrub("12:00 PM"))
        assertEquals("Alarm set for 6:45 AM.", scrub("Alarm set for 6:45 AM."))
    }

    @Test
    fun `handles multiple glitched times in one string`() {
        assertEquals(
            "one at 3:55 PM, one at 7:30 AM",
            scrub("one at 3:555 PM, one at 7:300 AM"),
        )
    }

    @Test
    fun `preserves case of period token`() {
        assertEquals("7:30 am", scrub("7:300 am"))
        assertEquals("7:30 Pm", scrub("7:300 Pm"))
    }

    @Test
    fun `ignores HH MM SS style times not followed by AM PM`() {
        // The pattern requires the AM/PM anchor, so 24h colon-times are safe.
        assertEquals("Set for 15:30:00 UTC.", scrub("Set for 15:30:00 UTC."))
    }

    @Test
    fun `ignores numbers not in time format`() {
        assertEquals("Saved 300 entries.", scrub("Saved 300 entries."))
        assertEquals("12 PM is noon", scrub("12 PM is noon"))
    }
}
