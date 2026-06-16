package com.contextsolutions.localagent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AckPhrasePickerTest {

    @Test
    fun always_returns_a_phrase_from_the_list() {
        val picker = AckPhrasePicker()
        repeat(50) { assertTrue(picker.next() in AckPhrasePicker.DEFAULT) }
    }

    @Test
    fun never_repeats_the_immediately_previous_phrase() {
        val picker = AckPhrasePicker()
        var prev = picker.next()
        repeat(200) {
            val next = picker.next()
            assertNotEquals("consecutive acks must differ", prev, next)
            prev = next
        }
    }

    @Test
    fun single_phrase_list_is_safe() {
        val picker = AckPhrasePicker(phrases = listOf("only one"))
        assertEquals("only one", picker.next())
        assertEquals("only one", picker.next())
    }
}
