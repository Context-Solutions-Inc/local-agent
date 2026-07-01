package com.contextsolutions.localagent.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechUtterance

/**
 * iOS [ChatSpeaker] actual — reads finalized assistant answers aloud via
 * `AVSpeechSynthesizer` (on-device, no permission, no network), the counterpart of
 * `AndroidTtsSpeaker` (Android `TextToSpeech`) and `DesktopTtsSpeaker` (invariant #42).
 *
 * [isSpeaking] is **load-bearing for dictation echo suppression** (#42): the shared
 * Chat screen pauses the mic while it's true so the recognizer doesn't transcribe the
 * assistant's own playback. We drive it ourselves rather than via an
 * `AVSpeechSynthesizerDelegate` — that protocol's `didStart`/`didFinish`/`didCancel`
 * methods all collapse to one Kotlin signature `(AVSpeechSynthesizer, AVSpeechUtterance)`,
 * which compiles (with `@ObjCSignatureOverride`) but does NOT reliably dispatch at
 * runtime, so `isSpeaking` never flipped and the echo leaked through. Instead [speak]
 * sets it true synchronously (before the first word plays) and a poll of the reliable
 * `AVSpeechSynthesizer.speaking` property clears it when playback ends.
 *
 * The synthesizer uses the application audio session, so it shares the `.playAndRecord`
 * session [IosSpeechDictation] configures.
 */
class IosChatSpeaker : ChatSpeaker {

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val synthesizer = AVSpeechSynthesizer()
    private val voice: AVSpeechSynthesisVoice? =
        AVSpeechSynthesisVoice.voiceWithLanguage(AVSpeechSynthesisVoice.currentLanguageCode())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null

    override fun speak(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        // Flush any in-progress utterance so the newest answer wins (QUEUE_FLUSH analog).
        if (synthesizer.speaking) synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        val utterance = AVSpeechUtterance.speechUtteranceWithString(trimmed).apply {
            voice?.let { setVoice(it) }
        }
        // Engage echo suppression immediately — before playback begins — so the mic is
        // paused by the time the first word is spoken (see class doc).
        _isSpeaking.value = true
        synthesizer.speakUtterance(utterance)
        startCompletionPoll()
    }

    override fun stop() {
        pollJob?.cancel()
        if (synthesizer.speaking) synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        _isSpeaking.value = false
    }

    /**
     * `speakUtterance` is asynchronous, so poll the reliable `speaking` property:
     * wait (bounded) for playback to actually begin, then wait until it ends, then
     * clear [isSpeaking]. Cancelled + replaced by the next [speak]/[stop].
     */
    private fun startCompletionPoll() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var waited = 0L
            while (isActive && !synthesizer.speaking && waited < START_TIMEOUT_MS) {
                delay(POLL_MS)
                waited += POLL_MS
            }
            while (isActive && synthesizer.speaking) {
                delay(POLL_MS)
            }
            _isSpeaking.value = false
        }
    }

    private companion object {
        const val POLL_MS = 100L
        const val START_TIMEOUT_MS = 2_000L
    }
}
