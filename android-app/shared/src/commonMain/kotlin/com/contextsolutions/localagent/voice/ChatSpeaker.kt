package com.contextsolutions.localagent.voice

import kotlinx.coroutines.flow.StateFlow

/**
 * Reads finalized assistant answers aloud (read-aloud / TTS), the cross-platform
 * seam for the desktop port (docs/DESKTOP_PORT_PLAN.md, Phase 7, invariant #42).
 *
 * Mirrors `:androidApp`'s existing `ChatSpeaker` (Android `TextToSpeech`-backed);
 * the desktop actual shells out to the OS speech engine
 * ([com.contextsolutions.localagent.voice.DesktopTtsSpeaker]). Promoted to
 * `commonMain` so the shared Chat ViewModel (Phase 9) can drive either platform's
 * speaker through one type. (`:androidApp` keeps its own `ChatSpeaker` under
 * `app.ui.chat` until the Chat screen moves to `:ui`; the two converge then.)
 */
interface ChatSpeaker {
    /** Speak [text], flushing any in-progress utterance. No-op on blank text. */
    fun speak(text: String)

    /** Stop any in-progress speech immediately. */
    fun stop()

    /**
     * `true` while an utterance is actively playing. Continuous dictation watches
     * this (plus a grace tail, #42) to suppress transcribing the assistant's own
     * voice (echo).
     */
    val isSpeaking: StateFlow<Boolean>
}
