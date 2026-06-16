package com.contextsolutions.localagent.voice

import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** First-run / lifecycle state of the Piper neural engine, surfaced to the Settings UI. */
sealed interface PiperState {
    data object Idle : PiperState
    data class Downloading(val fraction: Float) : PiperState
    data object Ready : PiperState
    data class Failed(val message: String) : PiperState

    /** No prebuilt Piper for this OS/arch — the engine isn't offered. */
    data object Unavailable : PiperState
}

/**
 * Desktop neural read-aloud via the bundled Piper engine (PR #66) — the high-quality
 * alternative to the OS speech engine. Fully self-contained: [PiperBinaryStore] +
 * [PiperVoiceStore] download the executable + voice model on first use (like the LLM /
 * Vosk models), then this synthesizes to raw PCM and plays it through the JVM's Java
 * Sound API (`javax.sound.sampled`) — **no `aplay`/system audio tool, no Python**.
 *
 * Stopping is clean and immediate (unlike `spd-say`, invariant #48): playback is in-JVM,
 * so [stop] closes the audio line and kills the piper process — no daemon outlives it.
 */
class PiperSpeechSynthesizer(
    private val binaryStore: PiperBinaryStore,
    private val voiceStore: PiperVoiceStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val logger: (String) -> Unit = { System.err.println("[Piper] $it") },
) {
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _state = MutableStateFlow<PiperState>(
        if (binaryStore.assetForHost() == null) PiperState.Unavailable else PiperState.Idle,
    )
    val state: StateFlow<PiperState> = _state.asStateFlow()

    /** True when a prebuilt Piper exists for this OS/arch (else the engine is hidden). */
    fun isAvailable(): Boolean = binaryStore.assetForHost() != null

    @Volatile private var process: Process? = null
    @Volatile private var line: SourceDataLine? = null
    @Volatile private var speakJob: Job? = null

    /** Pre-download the binary + [voiceId] without speaking (called when the user selects Piper). */
    fun prepare(voiceId: String?) {
        if (!isAvailable()) return
        scope.launch { ensure(voiceId) }
    }

    /** Synthesize [text] with [voiceId] at normalized [rate] and play it. Flushes any current utterance. */
    fun speak(text: String, voiceId: String?, rate: Int) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !isAvailable()) return
        stop()
        speakJob = scope.launch {
            val (binary, voice) = ensure(voiceId) ?: return@launch
            try {
                runPiper(binary, voice, trimmed, rate)
            } catch (t: Throwable) {
                logger("Piper synth/playback failed: ${t.message}")
            } finally {
                _isSpeaking.value = false
            }
        }
    }

    fun stop() {
        speakJob?.cancel()
        speakJob = null
        line?.let { runCatching { it.stop(); it.flush(); it.close() } }
        line = null
        process?.let { runCatching { it.destroy() } }
        process = null
        _isSpeaking.value = false
    }

    /** Download (if needed) the binary + voice. Updates [state]; returns null on failure. */
    private suspend fun ensure(voiceId: String?): Pair<File, PiperVoiceFiles>? {
        val spec = PiperVoices.byId(voiceId)
        val binaryTotal = binaryStore.assetForHost()?.sizeBytes ?: 0L
        val grandTotal = (binaryTotal + spec.totalBytes).coerceAtLeast(1L)

        val binary = binaryStore.ensure { done, _ ->
            _state.value = PiperState.Downloading((done.toFloat() / grandTotal).coerceIn(0f, 1f))
        } ?: run {
            _state.value = PiperState.Failed("Could not download the Piper engine.")
            return null
        }
        val voice = voiceStore.ensure(spec) { done, _ ->
            _state.value = PiperState.Downloading(((binaryTotal + done).toFloat() / grandTotal).coerceIn(0f, 1f))
        } ?: run {
            _state.value = PiperState.Failed("Could not download the Piper voice.")
            return null
        }
        _state.value = PiperState.Ready
        return binary to voice
    }

    private fun runPiper(binary: File, voice: PiperVoiceFiles, text: String, rate: Int) {
        val command = buildList {
            add(binary.absolutePath)
            add("--model"); add(voice.onnx.absolutePath)
            add("--config"); add("${voice.onnx.absolutePath}.json")
            add("--output-raw")
            val ls = lengthScaleFor(rate)
            if (ls != 1.0) { add("--length_scale"); add(ls.toString()) }
        }
        // Run from the binary's dir so it finds its bundled libs + espeak-ng-data ($ORIGIN).
        val proc = ProcessBuilder(command).directory(binary.parentFile).start()
        process = proc
        // Drain stderr (piper logs) so it never blocks; it must NOT mix into stdout PCM.
        scope.launch { runCatching { proc.errorStream.bufferedReader().forEachLine { } } }
        // Feed the utterance, then close stdin so piper synthesizes + exits on EOF.
        proc.outputStream.use { it.write((text + "\n").toByteArray()); it.flush() }
        playPcm(proc, voice.sampleRate)
        proc.waitFor()
    }

    /** Stream raw S16LE mono PCM from piper's stdout straight to a [SourceDataLine]. */
    private fun playPcm(proc: Process, sampleRate: Int) {
        val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false) // signed, little-endian
        val info = DataLine.Info(SourceDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) {
            logger("no audio output line for ${sampleRate}Hz PCM — read-aloud silent")
            return
        }
        val dataLine = AudioSystem.getLine(info) as SourceDataLine
        line = dataLine
        dataLine.open(format)
        dataLine.start()
        _isSpeaking.value = true
        proc.inputStream.use { input ->
            val buf = ByteArray(8 * 1024)
            while (true) {
                val n = try {
                    input.read(buf)
                } catch (_: Throwable) {
                    break // line closed by stop()
                }
                if (n < 0) break
                try {
                    dataLine.write(buf, 0, n)
                } catch (_: Throwable) {
                    break
                }
            }
        }
        runCatching { dataLine.drain(); dataLine.stop(); dataLine.close() }
        if (line === dataLine) line = null
    }

    companion object {
        /**
         * Map normalized rate [-100, 100] → Piper `--length_scale` (smaller = faster).
         * 0 → 1.0 (model default); +100 → 0.5 (2× faster); -100 → 1.5 (slower).
         */
        fun lengthScaleFor(rate: Int): Double =
            (1.0 - rate.coerceIn(-100, 100) / 200.0)
    }
}
