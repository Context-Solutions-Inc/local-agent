package com.contextsolutions.localagent.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the keyword set drives the right decision for common phrasings
 * a user might type into the chat. The detector is intentionally inclusive —
 * false positives just skip pre-flight (cheap), false negatives let
 * pre-flight fire a search on a clock command (expensive: Gemma gets
 * confused).
 */
class ClockIntentDetectorTest {

    private val detector = ClockIntentDetector()

    @Test
    fun `timer keyword variations all detected`() {
        assertTrue(detector.isClockIntent("set a 1-minute timer for tea"))
        assertTrue(detector.isClockIntent("Set a 25 minute timer for studying"))
        assertTrue(detector.isClockIntent("how long left on my timer?"))
        assertTrue(detector.isClockIntent("cancel all timers"))
    }

    @Test
    fun `alarm keyword variations all detected`() {
        assertTrue(detector.isClockIntent("set an alarm for 7am"))
        assertTrue(detector.isClockIntent("wake me at 6:30"))
        assertTrue(detector.isClockIntent("wake me up at 8"))
        assertTrue(detector.isClockIntent("what alarms do I have?"))
        assertTrue(detector.isClockIntent("snooze my alarm"))
    }

    @Test
    fun `remind me in X minutes pattern detected`() {
        assertTrue(detector.isClockIntent("remind me in 5 minutes to call mom"))
        assertTrue(detector.isClockIntent("in 25 min do something"))
        assertTrue(detector.isClockIntent("in 1 hour"))
    }

    @Test
    fun `non-clock messages do not match`() {
        assertFalse(detector.isClockIntent("what's the weather like?"))
        assertFalse(detector.isClockIntent("tell me about photosynthesis"))
        assertFalse(detector.isClockIntent("5 minutes ago someone called"))
        assertFalse(detector.isClockIntent("I went for a run yesterday"))
    }
}
