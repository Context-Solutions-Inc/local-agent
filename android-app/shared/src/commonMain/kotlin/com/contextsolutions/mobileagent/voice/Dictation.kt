package com.contextsolutions.mobileagent.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Continuous speech-to-text dictation, the cross-platform seam extracted for the
 * desktop port (docs/DESKTOP_PORT_PLAN.md, Phase 7, invariant #42).
 *
 * Mirrors the behaviour of `:androidApp`'s `SpeechDictation` (Android
 * `SpeechRecognizer`) but as an interface so the desktop Vosk actual
 * ([com.contextsolutions.mobileagent.voice.VoskDictation]) and a future shared
 * Chat ViewModel (Phase 9) share one type. Final transcripts arrive on [results]
 * (a hot flow, not a ctor callback) so the consumer can collect them like any
 * other UI event stream.
 *
 * Echo suppression (#42) is the consumer's job: while [ChatSpeaker.isSpeaking]
 * (plus a grace tail) it drops non-command transcripts; the impl keeps listening.
 */
interface Dictation {

    /** Finalized utterances, one per recognized phrase. Hot — collect while [start]ed. */
    val results: Flow<String>

    /** `true` while the microphone is actively capturing. */
    val isListening: StateFlow<Boolean>

    /** Begin (or resume) continuous listening. Safe to call repeatedly. */
    fun start()

    /** Pause listening; keeps the engine/model loaded. */
    fun stop()

    /** Tear down the engine + release the microphone + model. */
    fun destroy()
}
