package com.contextsolutions.localagent.voice

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Desktop [Dictation] (docs/DESKTOP_PORT_PLAN.md, Phase 7) — offline STT via Vosk
 * (JNI), the desktop counterpart of Android's `SpeechRecognizer`-backed
 * `SpeechDictation`. Fully offline, no network.
 *
 * Captures 16 kHz mono PCM from the default microphone (`javax.sound.sampled`)
 * and feeds it to a Vosk [Recognizer]; each completed utterance's text is emitted
 * on [results]. Continuous by construction (the capture loop runs until [stop]),
 * so there's no Android-style single-shot restart dance.
 *
 * The acoustic model (~40 MB) is acquired through [modelProvider] — by default
 * [VoskModelStore.ensure], which downloads + caches it under `<app-data>/models/vosk`
 * on first use (env override + manual drop still honoured). Acquisition is async, so
 * [start] launches a job that resolves the model (downloading if needed) and only then
 * opens the mic; [isListening] flips true once capture actually begins.
 *
 * **Degrades to no-op** when the model can't be obtained (offline first run, no disk)
 * or no microphone line is available — [start]'s job logs and returns, matching the
 * ONNX/GGUF "missing artifact ⇒ silent disable" pattern, so a headless CI box neither
 * errors nor blocks.
 */
class VoskDictation(
    private val modelProvider: suspend () -> String? = { VoskModelStore().ensure() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val logger: (String) -> Unit = { System.err.println("[Dictation] $it") },
) : Dictation {

    private val _results = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val results: Flow<String> = _results.asSharedFlow()

    private val _partials = MutableSharedFlow<String>(extraBufferCapacity = 32)
    override val partials: Flow<String> = _partials.asSharedFlow()

    private val _isListening = MutableStateFlow(false)
    override val isListening = _isListening.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var job: Job? = null

    override fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            val modelPath = modelProvider()
            if (modelPath == null) {
                logger("no Vosk model and it couldn't be downloaded — dictation disabled (check the network, or set LOCALAGENT_VOSK_MODEL)")
                return@launch
            }
            captureLoop(modelPath)
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        _isListening.value = false
    }

    override fun destroy() = stop()

    private suspend fun captureLoop(modelPath: String) {
        val format = AudioFormat(SAMPLE_RATE, 16, 1, true, false) // 16kHz, 16-bit, mono, signed, little-endian
        val info = DataLine.Info(TargetDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) {
            logger("no microphone line available — dictation disabled")
            return
        }
        var line: TargetDataLine? = null
        try {
            Model(modelPath).use { model ->
                Recognizer(model, SAMPLE_RATE).use { recognizer ->
                    line = (AudioSystem.getLine(info) as TargetDataLine).apply {
                        open(format)
                        start()
                    }
                    _isListening.value = true
                    val buffer = ByteArray(BUFFER_BYTES)
                    var lastPartial = ""
                    while (coroutineContext.isActive) {
                        val read = line.read(buffer, 0, buffer.size)
                        if (read <= 0) continue
                        if (recognizer.acceptWaveForm(buffer, read)) {
                            extractText(recognizer.result)?.let { _results.tryEmit(it) }
                            lastPartial = ""
                        } else {
                            // PR #67 — stream the in-progress transcript so words
                            // appear in the prompt box while talking. Vosk grows the
                            // `partial` field each frame; emit only on change to
                            // avoid spamming identical strings.
                            val partial = extractPartial(recognizer.partialResult)
                            if (partial != null && partial != lastPartial) {
                                lastPartial = partial
                                _partials.tryEmit(partial)
                            }
                        }
                    }
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            logger("Vosk capture failed: ${t.message}")
        } finally {
            line?.let { runCatching { it.stop(); it.close() } }
            _isListening.value = false
        }
    }

    private fun extractText(result: String): String? = try {
        json.parseToJsonElement(result).jsonObject["text"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    /** Vosk's `partialResult` carries the live transcript under `"partial"`. */
    private fun extractPartial(result: String): String? = try {
        json.parseToJsonElement(result).jsonObject["partial"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    private companion object {
        const val SAMPLE_RATE = 16_000f
        const val BUFFER_BYTES = 4096
    }
}
