package com.contextsolutions.mobileagent.ui.chat

/**
 * Spoken control words recognized during continuous dictation. The dictation
 * callback matches each finalized utterance against these BEFORE appending it
 * as text, so a standalone "send" / "cancel" / "clear" / "new chat" fires the
 * action instead of being typed into the box.
 *
 * Matching is whole-utterance only — the recognizer segments on speech pauses,
 * so "send me the report" stays dictation text; only an utterance that *is* a
 * command phrase triggers the action. Trigger phrases are kept tight on purpose
 * because a voice "send" is irreversible (no review step like typing).
 */
enum class VoiceCommand {
    SEND,
    CANCEL,
    CLEAR,
    NEW_CHAT,
    MIC_OFF,
    SPEAKER_OFF,
    SPEAKER_ON,
    NONE;

    companion object {
        private val PHRASES: Map<String, VoiceCommand> = buildMap {
            listOf("send", "send it", "send message", "submit").forEach { put(it, SEND) }
            listOf("cancel", "cancel that").forEach { put(it, CANCEL) }
            listOf("clear", "clear text", "clear input", "clear that").forEach { put(it, CLEAR) }
            listOf("new chat", "new conversation", "start new chat").forEach { put(it, NEW_CHAT) }
            // Device-control commands. There's no "microphone on" — the mic is
            // off when not listening, so nothing would hear it.
            listOf("microphone off", "mic off", "turn off microphone", "turn off the microphone")
                .forEach { put(it, MIC_OFF) }
            listOf("speaker off", "turn off speaker", "turn off the speaker")
                .forEach { put(it, SPEAKER_OFF) }
            listOf("speaker on", "turn on speaker", "turn on the speaker")
                .forEach { put(it, SPEAKER_ON) }
        }

        /** The command an utterance maps to, or [NONE] when it's dictation text. */
        fun match(spoken: String): VoiceCommand = PHRASES[normalize(spoken)] ?: NONE

        private fun normalize(spoken: String): String =
            spoken.trim()
                .lowercase()
                .trim('.', '!', '?', ',')
                .replace(Regex("\\s+"), " ")
    }
}
