package com.contextsolutions.localagent.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Continuous speech-to-text dictation, the cross-platform seam extracted for the
 * desktop port (docs/DESKTOP_PORT_PLAN.md, Phase 7, invariant #42).
 *
 * Mirrors the behaviour of `:androidApp`'s `SpeechDictation` (Android
 * `SpeechRecognizer`) but as an interface so the desktop Vosk actual
 * ([com.contextsolutions.localagent.voice.VoskDictation]) and a future shared
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

    /**
     * Live, in-progress transcript of the CURRENT utterance as the engine refines
     * it (Android `onPartialResults`, Vosk `partialResult`). Emitted as the user
     * speaks so the consumer can show the words appearing in the prompt box before
     * the phrase finalizes (PR #67); the text can change mid-utterance and is
     * superseded by the matching [results] emission when the phrase ends. May be
     * blank between utterances. Voice-command matching ([VoiceCommand]) still keys
     * off [results] (whole finalized utterances) — partials are display-only.
     */
    val partials: Flow<String>

    /** `true` while the microphone is actively capturing. */
    val isListening: StateFlow<Boolean>

    /** Begin (or resume) continuous listening. Safe to call repeatedly. */
    fun start()

    /** Pause listening; keeps the engine/model loaded. */
    fun stop()

    /** Tear down the engine + release the microphone + model. */
    fun destroy()
}
