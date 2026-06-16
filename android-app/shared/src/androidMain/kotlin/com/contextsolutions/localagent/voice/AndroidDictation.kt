package com.contextsolutions.localagent.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android [Dictation] actual — continuous in-app STT on [SpeechRecognizer], the
 * counterpart of desktop's `VoskDictation` (invariant #42). The recognizer is
 * single-shot (stops after a speech pause), so "continuous" means restarting it
 * on every result/timeout while [start]ed. Each finalized utterance is emitted on
 * [results]; the consumer (`ChatViewModel`-driven Chat screen) matches voice
 * commands + appends dictation text, dropping non-command text during TTS playback
 * (echo suppression, #42).
 *
 * Must be driven from the main thread ([SpeechRecognizer] requirement); the
 * Compose caller satisfies this. Needs the `RECORD_AUDIO` runtime permission
 * (the chat surface requests it via the `MicPermission` seam). Moved from
 * `:androidApp`'s `SpeechDictation` in Phase 9; the ctor callback became the
 * [results] hot flow the [Dictation] interface prescribes.
 */
class AndroidDictation(context: Context) : Dictation {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var wantListening = false

    private val _results = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val results: Flow<String> = _results.asSharedFlow()

    private val _partials = MutableSharedFlow<String>(extraBufferCapacity = 32)
    override val partials: Flow<String> = _partials.asSharedFlow()

    private val _isListening = MutableStateFlow(false)
    override val isListening = _isListening.asStateFlow()

    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
        // PR #67 — stream the live transcript so the user sees words appear in the
        // prompt box while talking. The final `onResults` still drives voice-command
        // matching + the committed text; partials are display-only.
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }

    override fun start() {
        wantListening = true
        ensureRecognizer()
        beginListening()
    }

    override fun stop() {
        wantListening = false
        handler.removeCallbacksAndMessages(null)
        if (_isListening.value) runCatching { recognizer?.cancel() }
        _isListening.value = false
    }

    override fun destroy() {
        stop()
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun ensureRecognizer() {
        if (recognizer != null) return
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
            setRecognitionListener(listener)
        }
    }

    private fun beginListening() {
        if (!wantListening || _isListening.value) return
        val r = recognizer ?: return
        _isListening.value = true
        runCatching { r.startListening(intent) }
            .onFailure {
                _isListening.value = false
                Log.w(TAG, "startListening failed", it)
            }
    }

    /** Schedule a restart after a short delay so rapid cycles don't busy-loop. */
    private fun restartSoon() {
        _isListening.value = false
        if (!wantListening) return
        handler.postDelayed({ beginListening() }, RESTART_DELAY_MS)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle) {
            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { _results.tryEmit(it) }
            restartSoon()
        }

        override fun onError(error: Int) {
            // Permission revoked mid-session is genuinely fatal; everything else
            // (no-match, timeout, recognizer-busy) is a normal pause in a
            // continuous loop — restart after the debounce delay.
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                _isListening.value = false
                Log.w(TAG, "dictation stopped: insufficient permissions")
                return
            }
            restartSoon()
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { _partials.tryEmit(it) }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private companion object {
        const val TAG = "SpeechDictation"
        const val RESTART_DELAY_MS = 150L
    }
}
