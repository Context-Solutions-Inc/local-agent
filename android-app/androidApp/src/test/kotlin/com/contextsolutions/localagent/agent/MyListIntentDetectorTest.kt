package com.contextsolutions.localagent.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the "my list" gate used by [MyListIntentDetector] (PR #99) — the
 * literal phrase "my list" is required, and bare "list" / "todo" / "task"
 * deliberately do NOT fire. The detector is the load-bearing gate for the
 * "no LLM fallback on partial-match" contract: a false positive sends a real
 * chat turn to the guidance reply, and "list"/"todo" are far too common to
 * gate on.
 */
class MyListIntentDetectorTest {

    private val detector = MyListIntentDetector()

    @Test
    fun `my list phrase fires`() {
        assertTrue(detector.isMyListIntent("add buy milk to my list"))
        assertTrue(detector.isMyListIntent("show my list"))
        assertTrue(detector.isMyListIntent("what's on my list"))
        assertTrue(detector.isMyListIntent("complete #2 on my list"))
    }

    @Test
    fun `my lists plural fires`() {
        assertTrue(detector.isMyListIntent("show me my lists"))
    }

    @Test
    fun `bare list without possessive does NOT fire`() {
        // "list" alone is far too general — this is the whole point of PR #99.
        assertFalse(detector.isMyListIntent("list the planets in order"))
        assertFalse(detector.isMyListIntent("give me a list of ideas"))
        assertFalse(detector.isMyListIntent("what's on the agenda"))
    }

    @Test
    fun `todo and task vocabulary no longer fires`() {
        // Dropped in PR #99 — STT mis-hears "todo"; the surface is "my list".
        assertFalse(detector.isMyListIntent("add buy milk to my todos"))
        assertFalse(detector.isMyListIntent("delete the gym task"))
        assertFalse(detector.isMyListIntent("what's on my to-do list"))
    }

    @Test
    fun `a different list does NOT fire`() {
        // Only the dedicated "my list" surface — not arbitrary other lists.
        assertFalse(detector.isMyListIntent("add milk to my shopping list"))
    }

    @Test
    fun `clock vocabulary does NOT fire`() {
        assertFalse(detector.isMyListIntent("set a 5 minute timer"))
        assertFalse(detector.isMyListIntent("wake me at 7am"))
        assertFalse(detector.isMyListIntent("remind me in 5 minutes"))
    }

    @Test
    fun `weather and trivia do NOT fire`() {
        assertFalse(detector.isMyListIntent("what's the weather in Toronto"))
        assertFalse(detector.isMyListIntent("who won the world series"))
    }
}
