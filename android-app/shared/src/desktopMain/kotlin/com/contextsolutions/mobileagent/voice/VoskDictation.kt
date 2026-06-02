package com.contextsolutions.mobileagent.voice

import com.contextsolutions.mobileagent.inference.DesktopAppDirs
import java.io.File
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
 * **Degrades to no-op** when the acoustic model is absent (no
 * `MOBILEAGENT_VOSK_MODEL` env and no `<app-data>/models/vosk` directory) or no
 * microphone line is available — [start] logs and returns, matching the
 * ONNX/GGUF "missing artifact ⇒ silent disable" pattern, so a headless CI box
 * neither errors nor blocks.
 */
class VoskDictation(
    private val modelPathProvider: () -> String? = { defaultModelPath() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val logger: (String) -> Unit = { System.err.println("[Dictation] $it") },
) : Dictation {

    private val _results = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val results: Flow<String> = _results.asSharedFlow()

    private val _isListening = MutableStateFlow(false)
    override val isListening = _isListening.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var job: Job? = null

    override fun start() {
        if (job?.isActive == true) return
        val modelPath = modelPathProvider()
        if (modelPath == null) {
            logger("no Vosk model (set MOBILEAGENT_VOSK_MODEL or drop one in <app-data>/models/vosk) — dictation disabled")
            return
        }
        job = scope.launch { captureLoop(modelPath) }
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
                    while (coroutineContext.isActive) {
                        val read = line.read(buffer, 0, buffer.size)
                        if (read <= 0) continue
                        if (recognizer.acceptWaveForm(buffer, read)) {
                            extractText(recognizer.result)?.let { _results.tryEmit(it) }
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

    private companion object {
        const val SAMPLE_RATE = 16_000f
        const val BUFFER_BYTES = 4096

        fun defaultModelPath(): String? =
            System.getenv("MOBILEAGENT_VOSK_MODEL")?.takeIf { it.isNotBlank() }
                ?: File(DesktopAppDirs.dataDir(), "models/vosk").takeIf { it.isDirectory }?.absolutePath
    }
}
