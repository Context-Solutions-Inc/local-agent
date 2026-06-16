package com.contextsolutions.localagent.memory

import com.contextsolutions.localagent.memory.RememberForgetDetector.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RememberForgetDetectorTest {

    private val detector = RememberForgetDetector()

    // -- Remember -----------------------------------------------------------

    @Test
    fun matches_remember_that_form() {
        val command = detector.classify("remember that I'm allergic to peanuts")
        assertTrue(command is Command.Remember)
        assertEquals("I'm allergic to peanuts", (command as Command.Remember).payload)
    }

    @Test
    fun matches_remember_I_form() {
        val command = detector.classify("Remember I prefer dark roast coffee")
        assertTrue(command is Command.Remember)
        assertEquals("prefer dark roast coffee", (command as Command.Remember).payload)
    }

    @Test
    fun matches_optional_please_prefix() {
        val command = detector.classify("Please remember that I work remotely")
        assertTrue(command is Command.Remember)
    }

    @Test
    fun matches_save_synonym() {
        val command = detector.classify("save this: my zoom link is example.com")
        assertTrue(command is Command.Remember)
    }

    @Test
    fun matches_note_synonym() {
        val command = detector.classify("note that I'm a vegetarian")
        assertTrue(command is Command.Remember)
        assertEquals("I'm a vegetarian", (command as Command.Remember).payload)
    }

    @Test
    fun is_case_insensitive() {
        val command = detector.classify("REMEMBER THAT I HAVE A DOG")
        assertTrue(command is Command.Remember)
    }

    @Test
    fun matches_possessive_forms() {
        // Locks the bug-fix from on-device review: "Remember my dog's
        // name is Evie" used to fall through to the classifier (which
        // misses on RELATIONSHIP-shaped facts at v1.0 recall) because
        // "my" wasn't in the connector alternation.
        val cases = listOf(
            "Remember my dog's name is Evie" to "dog's name is Evie",
            "remember my anniversary is May 5" to "anniversary is May 5",
            "remember our wedding is in June" to "wedding is in June",
            "Remember her birthday is on Tuesday" to "birthday is on Tuesday",
        )
        for ((text, expectedPayload) in cases) {
            val command = detector.classify(text)
            assertTrue("'$text' should be Remember, got $command", command is Command.Remember)
            assertEquals(expectedPayload, (command as Command.Remember).payload)
        }
    }

    @Test
    fun matches_determiner_and_interrogative_forms() {
        val cases = listOf(
            "remember the time we met in Paris",
            "remember when we drove to the coast",
            "remember where I parked the car",
            "remember how to make sourdough",
        )
        for (text in cases) {
            val command = detector.classify(text)
            assertTrue("'$text' should be Remember, got $command", command is Command.Remember)
        }
    }

    // -- Forget -------------------------------------------------------------

    @Test
    fun matches_forget_what_i_said_about() {
        val command = detector.classify("forget what I said about my job")
        assertTrue(command is Command.Forget)
        assertEquals("my job", (command as Command.Forget).payload)
    }

    @Test
    fun matches_forget_about() {
        val command = detector.classify("forget about my anniversary date")
        assertTrue(command is Command.Forget)
        assertEquals("my anniversary date", (command as Command.Forget).payload)
    }

    @Test
    fun matches_delete_the_memory_about() {
        val command = detector.classify("delete the memory about my address")
        assertTrue(command is Command.Forget)
    }

    @Test
    fun forget_matches_possessive_forms() {
        val cases = listOf(
            "forget my dog's name" to "dog's name",
            "forget her birthday" to "birthday",
            "forget our wedding date" to "wedding date",
        )
        for ((text, expectedPayload) in cases) {
            val command = detector.classify(text)
            assertTrue("'$text' should be Forget, got $command", command is Command.Forget)
            assertEquals(expectedPayload, (command as Command.Forget).payload)
        }
    }

    // -- Negative cases -----------------------------------------------------

    @Test
    fun does_not_trigger_on_casual_phrasings() {
        // Without an explicit prefix the user message goes through the
        // classifier path; the detector should not steal these.
        val cases = listOf(
            "I want to remember this for next time",
            "you should know I'm allergic to peanuts",
            "I'm going to forget that ever happened",
            "what did I tell you yesterday",
        )
        for (text in cases) {
            assertEquals(
                "expected None for '$text', got ${detector.classify(text)}",
                Command.None,
                detector.classify(text),
            )
        }
    }

    @Test
    fun aborts_when_remember_has_no_payload() {
        // "remember that " with whitespace + nothing — payload is empty,
        // so we fall through to None and let the classifier path try.
        assertEquals(Command.None, detector.classify("remember that"))
    }

    @Test
    fun aborts_when_forget_has_no_payload() {
        assertEquals(Command.None, detector.classify("forget that"))
        assertEquals(Command.None, detector.classify("forget"))
    }

    @Test
    fun blank_input_returns_none() {
        assertEquals(Command.None, detector.classify(""))
        assertEquals(Command.None, detector.classify("   "))
    }
}
