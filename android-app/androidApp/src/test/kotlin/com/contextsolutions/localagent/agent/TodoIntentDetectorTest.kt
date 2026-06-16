package com.contextsolutions.localagent.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the narrow keyword set used by [TodoIntentDetector] — positive
 * cases for the verbs that should fire and negative cases for prose that
 * should NOT. The detector is the load-bearing gate for the "no LLM
 * fallback on partial-match" contract: a false positive sends a real chat
 * turn to the guidance reply.
 */
class TodoIntentDetectorTest {

    private val detector = TodoIntentDetector()

    @Test
    fun `bare todo keyword fires`() {
        assertTrue(detector.isTodoIntent("add buy milk to my todos"))
        assertTrue(detector.isTodoIntent("list my todos"))
        assertTrue(detector.isTodoIntent("complete the gym todo"))
    }

    @Test
    fun `to-do hyphen variant fires`() {
        assertTrue(detector.isTodoIntent("what's on my to-do list"))
    }

    @Test
    fun `task with management verb fires`() {
        assertTrue(detector.isTodoIntent("delete the gym task"))
        assertTrue(detector.isTodoIntent("add a high priority task"))
        assertTrue(detector.isTodoIntent("rename my task"))
    }

    @Test
    fun `cross off fires`() {
        assertTrue(detector.isTodoIntent("cross off the gym task"))
    }

    @Test
    fun `tasks word alone in prose does NOT fire`() {
        // Common false-positive trigger; the verb gate keeps this safe.
        assertFalse(detector.isTodoIntent("we have a lot of tasks at work today"))
        assertFalse(detector.isTodoIntent("I'm working on several tasks"))
    }

    @Test
    fun `clock vocabulary does NOT fire todo intent`() {
        // Clock detector wins precedence in the agent loop, but the todo
        // detector also stays clear of clock phrases on its own.
        assertFalse(detector.isTodoIntent("set a 5 minute timer"))
        assertFalse(detector.isTodoIntent("wake me at 7am"))
        assertFalse(detector.isTodoIntent("remind me in 5 minutes"))
        assertFalse(detector.isTodoIntent("cancel my tea timer"))
    }

    @Test
    fun `weather and trivia do NOT fire`() {
        assertFalse(detector.isTodoIntent("what's the weather in Toronto"))
        assertFalse(detector.isTodoIntent("who won the world series"))
    }
}
