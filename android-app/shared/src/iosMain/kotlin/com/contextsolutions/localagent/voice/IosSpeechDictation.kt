package com.contextsolutions.localagent.voice

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetooth
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.setActive
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognitionTask

/**
 * iOS [Dictation] actual — continuous in-app STT on `SFSpeechRecognizer` fed by an
 * `AVAudioEngine` input tap, the counterpart of `AndroidDictation` (Android
 * `SpeechRecognizer`) and desktop `VoskDictation` (invariant #42).
 *
 * A recognition task ends after a speech pause (or the ~1-minute limit), so
 * "continuous" means starting a fresh request/task on each final result or error
 * while [start]ed — mirroring `AndroidDictation`'s self-restart. The audio engine +
 * input tap stay up across restarts; only the request/task cycle. Each finalized
 * utterance is emitted on [results]; live transcripts stream on [partials] (PR #67,
 * display-only). Voice-command matching keys off [results] in the shared Chat screen.
 *
 * **On-device preference (prefer-on-device, fall back):** `requiresOnDeviceRecognition`
 * is set only when `supportsOnDeviceRecognition` is true, else recognition uses Apple's
 * server path. Needs the mic + speech-recognition permissions (requested via the
 * `MicPermission` seam) and the `NSMicrophoneUsageDescription` /
 * `NSSpeechRecognitionUsageDescription` Info.plist keys.
 */
@OptIn(ExperimentalForeignApi::class)
class IosSpeechDictation(
    private val logger: (String) -> Unit = {},
) : Dictation {

    private val _results = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val results: Flow<String> = _results.asSharedFlow()

    private val _partials = MutableSharedFlow<String>(extraBufferCapacity = 32)
    override val partials: Flow<String> = _partials.asSharedFlow()

    private val _isListening = MutableStateFlow(false)
    override val isListening = _isListening.asStateFlow()

    // All audio-engine / task mutation happens on the main queue for consistency.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val recognizer: SFSpeechRecognizer? = SFSpeechRecognizer()
    private val audioEngine = AVAudioEngine()

    private var wantListening = false
    private var engineRunning = false
    private var request: SFSpeechAudioBufferRecognitionRequest? = null
    private var task: SFSpeechRecognitionTask? = null

    override fun start() {
        wantListening = true
        scope.launch { ensureEngineAndListen() }
    }

    override fun stop() {
        wantListening = false
        // Do NOT deactivate the AVAudioSession here. stop() is also used to pause the
        // mic while the assistant reads aloud (echo control, ChatScreen), and TTS
        // (AVSpeechSynthesizer) plays through this SAME shared session — deactivating
        // it would cut off the assistant's playback. Just stop capturing; keep the
        // session active. destroy() (screen leaves composition) releases it.
        scope.launch { teardown(deactivateSession = false) }
    }

    override fun destroy() {
        wantListening = false
        scope.launch { teardown(deactivateSession = true) }
    }

    private suspend fun ensureEngineAndListen() {
        val rec = recognizer ?: run { logger("no SFSpeechRecognizer for locale"); return }
        if (!rec.available) { logger("recognizer unavailable; retrying"); restartSoon(); return }
        if (!engineRunning) {
            if (!startEngine()) { restartSoon(); return }
        }
        beginRecognition(rec)
    }

    private fun startEngine(): Boolean {
        val session = AVAudioSession.sharedInstance()
        val ok = session.setCategory(
            AVAudioSessionCategoryPlayAndRecord,
            withOptions = AVAudioSessionCategoryOptionDuckOthers or
                AVAudioSessionCategoryOptionDefaultToSpeaker or
                AVAudioSessionCategoryOptionAllowBluetooth,
            error = null,
        )
        if (!ok) { logger("setCategory failed"); return false }
        if (!session.setActive(true, error = null)) { logger("setActive failed"); return false }

        val input = audioEngine.inputNode
        val format = input.inputFormatForBus(0u)
        // Feed captured audio into whichever request is currently active.
        input.installTapOnBus(0u, bufferSize = 1024u, format = format) { buffer, _ ->
            buffer?.let { request?.appendAudioPCMBuffer(it) }
        }
        audioEngine.prepare()
        return if (audioEngine.startAndReturnError(null)) {
            engineRunning = true
            true
        } else {
            logger("audioEngine start failed")
            input.removeTapOnBus(0u)
            false
        }
    }

    private fun beginRecognition(rec: SFSpeechRecognizer) {
        val req = SFSpeechAudioBufferRecognitionRequest().apply {
            shouldReportPartialResults = true
            if (rec.supportsOnDeviceRecognition) requiresOnDeviceRecognition = true
        }
        request = req
        _isListening.value = true
        task = rec.recognitionTaskWithRequest(req) { result, error ->
            if (result != null) {
                val text = result.bestTranscription.formattedString
                if (result.isFinal()) {
                    if (text.isNotBlank()) _results.tryEmit(text)
                    scope.launch { restartNow() }
                } else if (text.isNotBlank()) {
                    _partials.tryEmit(text)
                }
            }
            if (error != null) {
                logger("recognition error: ${error.localizedDescription}")
                scope.launch { restartNow() }
            }
        }
    }

    /** End the current request/task (keep the engine up) and start a fresh one if still wanted. */
    private suspend fun restartNow() {
        endRecognition()
        if (wantListening) {
            val rec = recognizer ?: return
            if (rec.available) beginRecognition(rec) else restartSoon()
        }
    }

    /** Debounced restart so a rapidly-failing session doesn't busy-loop. */
    private fun restartSoon() {
        _isListening.value = false
        if (!wantListening) return
        scope.launch {
            delay(RESTART_DELAY_MS)
            if (wantListening) ensureEngineAndListen()
        }
    }

    private fun endRecognition() {
        request?.endAudio()
        task?.cancel()
        request = null
        task = null
    }

    private fun teardown(deactivateSession: Boolean) {
        endRecognition()
        _isListening.value = false
        if (engineRunning) {
            audioEngine.stop()
            audioEngine.inputNode.removeTapOnBus(0u)
            engineRunning = false
        }
        if (deactivateSession) {
            AVAudioSession.sharedInstance().setActive(false, error = null)
        }
    }

    private companion object {
        const val RESTART_DELAY_MS = 150L
    }
}
