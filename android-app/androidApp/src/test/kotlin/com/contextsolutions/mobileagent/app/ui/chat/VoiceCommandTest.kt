package com.contextsolutions.mobileagent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the spoken-command matcher: standalone command phrases fire actions,
 * but a phrase used inside a sentence stays dictation text (no mis-fire).
 */
class VoiceCommandTest {

    @Test
    fun matches_send_phrases() {
        assertEquals(VoiceCommand.SEND, VoiceCommand.match("send"))
        assertEquals(VoiceCommand.SEND, VoiceCommand.match("send it"))
        assertEquals(VoiceCommand.SEND, VoiceCommand.match("submit"))
    }

    @Test
    fun matches_cancel_clear_and_new_chat() {
        assertEquals(VoiceCommand.CANCEL, VoiceCommand.match("cancel"))
        assertEquals(VoiceCommand.CLEAR, VoiceCommand.match("clear"))
        assertEquals(VoiceCommand.NEW_CHAT, VoiceCommand.match("new chat"))
        assertEquals(VoiceCommand.NEW_CHAT, VoiceCommand.match("new conversation"))
    }

    @Test
    fun is_case_punctuation_and_whitespace_insensitive() {
        assertEquals(VoiceCommand.SEND, VoiceCommand.match("Send."))
        assertEquals(VoiceCommand.NEW_CHAT, VoiceCommand.match("  New   Chat!  "))
        assertEquals(VoiceCommand.CANCEL, VoiceCommand.match("Cancel that"))
    }

    @Test
    fun matches_device_control_commands() {
        assertEquals(VoiceCommand.MIC_OFF, VoiceCommand.match("microphone off"))
        assertEquals(VoiceCommand.MIC_OFF, VoiceCommand.match("Mic off."))
        assertEquals(VoiceCommand.SPEAKER_OFF, VoiceCommand.match("speaker off"))
        assertEquals(VoiceCommand.SPEAKER_OFF, VoiceCommand.match("turn off the speaker"))
        assertEquals(VoiceCommand.SPEAKER_ON, VoiceCommand.match("speaker on"))
        assertEquals(VoiceCommand.SPEAKER_ON, VoiceCommand.match("turn on the speaker"))
    }

    @Test
    fun does_not_fire_on_command_word_inside_a_sentence() {
        assertEquals(VoiceCommand.NONE, VoiceCommand.match("send me the report tomorrow"))
        assertEquals(VoiceCommand.NONE, VoiceCommand.match("let's start a new chat about this"))
        assertEquals(VoiceCommand.NONE, VoiceCommand.match("clear the table after dinner"))
    }

    @Test
    fun unknown_utterance_is_dictation_text() {
        assertEquals(VoiceCommand.NONE, VoiceCommand.match("hello there"))
    }
}
