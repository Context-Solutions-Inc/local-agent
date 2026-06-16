package com.contextsolutions.localagent.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionDetectorTest {

    private val detector = QuestionDetector()

    // -- Positive cases (must be flagged) ---------------------------------

    @Test
    fun flags_wh_questions() {
        assertTrue(detector.isQuestionOrRecall("what is my favorite sports team"))
        assertTrue(detector.isQuestionOrRecall("Who did I tell you was my best friend"))
        assertTrue(detector.isQuestionOrRecall("where do I live"))
        assertTrue(detector.isQuestionOrRecall("WHEN did I say my anniversary was"))
        assertTrue(detector.isQuestionOrRecall("why is the sky blue"))
        assertTrue(detector.isQuestionOrRecall("How do you know my favorite team"))
        assertTrue(detector.isQuestionOrRecall("Which team is my favorite"))
    }

    @Test
    fun flags_yes_no_and_modal_questions() {
        assertTrue(detector.isQuestionOrRecall("is the Blue Jays my favorite team"))
        assertTrue(detector.isQuestionOrRecall("are we in Toronto today"))
        assertTrue(detector.isQuestionOrRecall("can you remind me of my anniversary"))
        assertTrue(detector.isQuestionOrRecall("do you remember my favorite color"))
        assertTrue(detector.isQuestionOrRecall("did I tell you about Toronto"))
    }

    @Test
    fun flags_contraction_starts() {
        assertTrue(detector.isQuestionOrRecall("what's my favorite team"))
        assertTrue(detector.isQuestionOrRecall("who's my best friend"))
        assertTrue(detector.isQuestionOrRecall("where's the Blue Jays' stadium"))
    }

    @Test
    fun flags_recall_request_prefixes() {
        assertTrue(detector.isQuestionOrRecall("tell me my favorite team"))
        assertTrue(detector.isQuestionOrRecall("remind me of my anniversary"))
        assertTrue(detector.isQuestionOrRecall("show me what you remember about me"))
        assertTrue(detector.isQuestionOrRecall("do you remember my dog's name"))
        assertTrue(detector.isQuestionOrRecall("what about my dog"))
    }

    @Test
    fun flags_trailing_question_mark_even_with_unusual_phrasing() {
        assertTrue(detector.isQuestionOrRecall("my favorite team?"))
        assertTrue(detector.isQuestionOrRecall("I'm allergic to peanuts, right?"))
    }

    // -- Negative cases (must NOT be flagged) -----------------------------

    @Test
    fun ignores_declarative_statements_about_user() {
        assertFalse(detector.isQuestionOrRecall("my favorite team is the Toronto Blue Jays"))
        assertFalse(detector.isQuestionOrRecall("I work at Anthropic"))
        assertFalse(detector.isQuestionOrRecall("I have a dog named Evie"))
        assertFalse(detector.isQuestionOrRecall("Toronto is where I live"))
    }

    @Test
    fun ignores_empty_or_whitespace() {
        assertFalse(detector.isQuestionOrRecall(""))
        assertFalse(detector.isQuestionOrRecall("   "))
        assertFalse(detector.isQuestionOrRecall("\n\t  "))
    }

    @Test
    fun ignores_explicit_remember_forget_commands() {
        // These never reach the QuestionDetector (RememberForgetDetector
        // runs first), but checking anyway confirms the detector
        // wouldn't false-positive on them.
        assertFalse(detector.isQuestionOrRecall("Remember that I'm allergic to peanuts"))
        assertFalse(detector.isQuestionOrRecall("Please remember my favorite color is blue"))
        assertFalse(detector.isQuestionOrRecall("Forget what I told you about my job"))
    }
}
