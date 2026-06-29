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
import kotlinx.coroutines.delay
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
import com.contextsolutions.localagent.notification.DesktopOs
import com.contextsolutions.localagent.platform.DesktopDiag
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Desktop [Dictation] (docs/DESKTOP_PORT_PLAN.md, Phase 7) — offline STT via Vosk
 * (JNI), the desktop counterpart of Android's `SpeechRecognizer`-backed
 * `SpeechDictation`. Fully offline, no network.
 *
 * Captures 16 kHz mono PCM from the default microphone and feeds it to a Vosk
 * [Recognizer]; each completed utterance's text is emitted on [results]. Continuous by
 * construction (the capture loop runs until [stop]), so there's no Android-style
 * single-shot restart dance.
 *
 * The acoustic model (~40 MB) is acquired through [modelProvider] — by default
 * [VoskModelStore.ensure], which downloads + caches it under `<app-data>/models/vosk`
 * on first use (env override + manual drop still honoured). Acquisition is async, so
 * [start] launches a job that resolves the model (downloading if needed) and only then
 * opens the mic; [isListening] flips true once capture actually begins.
 *
 * **Degrades to no-op** when the model can't be obtained (offline first run, no disk)
 * or no microphone is available — [start]'s job logs and returns, matching the
 * ONNX/GGUF "missing artifact ⇒ silent disable" pattern, so a headless CI box neither
 * errors nor blocks.
 *
 * ### Capture source — recorder subprocess on Linux, `javax.sound` elsewhere
 *
 * On Linux a laptop **suspend/resume** reclaims the audio device; the JVM's
 * `javax.sound.sampled` then hands back a [TargetDataLine] that opens "successfully" but
 * delivers `read() == 0` forever (the device handle is cached/stale and reopening it does
 * not recover — confirmed on-device). So on Linux we capture through a spawned recorder
 * CLI (`parec` → `arecord`, auto-detected) piped as raw S16LE/16 kHz/mono into Vosk —
 * the same process+PCM pattern as [PiperSpeechSynthesizer]. A **fresh process per session**
 * binds to the *current* default source, so it survives suspend/resume. macOS/Windows (and
 * Linux with no recorder CLI) keep the [TargetDataLine] path.
 *
 * ### Recovery loop + watchdog
 *
 * Capture runs as reopenable per-mic sessions: a session ends [SessionEnd.Stale] on a stale
 * read (recorder EOF, a long run of empty line reads, or a throwing read — see
 * [classifyRead]) or when the [stallWatchdog] force-closes a silent source, and the outer
 * loop reopens (keeping the [Model]/[Recognizer] alive) with bounded backoff. A session that
 * never produces audio counts toward [MAX_BARREN_SESSIONS] so a permanently-gone device
 * can't spin forever. The watchdog is the catch-all for a source wedged with no return value:
 * closing it from another thread unblocks the read.
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

    @Volatile
    private var watchdog: Job? = null

    /**
     * The live capture source. Held at class scope (not loop-local) so the [stallWatchdog]
     * can `close()` it from another coroutine to break a wedged read.
     */
    @Volatile
    private var currentSource: MicSource? = null

    /** Wall-clock of the last frame that actually carried audio; drives the watchdog. */
    @Volatile
    private var lastAudioAtMs: Long = 0L

    /** Set once a session reads real audio; resets the [MAX_BARREN_SESSIONS] guard. */
    @Volatile
    private var lastSessionProducedAudio = false

    override fun start() {
        if (job?.isActive == true) return
        lastAudioAtMs = System.currentTimeMillis()
        watchdog = scope.launch { stallWatchdog() }
        job = scope.launch {
            try {
                val modelPath = modelProvider()
                if (modelPath == null) {
                    logger("no Vosk model and it couldn't be downloaded — dictation disabled (check the network, or set LOCALAGENT_VOSK_MODEL)")
                    return@launch
                }
                captureLoop(modelPath)
            } finally {
                watchdog?.cancel()
                watchdog = null
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        watchdog?.cancel()
        watchdog = null
        // Closing the source unblocks any in-flight read so the session coroutine unwinds.
        currentSource?.let { runCatching { it.close() } }
        currentSource = null
        _isListening.value = false
    }

    override fun destroy() = stop()

    /**
     * Owns the [Model]/[Recognizer] (alive across reopens) and the reopen-with-backoff loop.
     * Each [runCaptureSession] opens one mic source and reads until it ends; a
     * [SessionEnd.Stale] end triggers recovery, [SessionEnd.Cancelled] stops.
     */
    private suspend fun captureLoop(modelPath: String) {
        val format = AudioFormat(SAMPLE_RATE, 16, 1, true, false) // 16kHz, 16-bit, mono, signed, little-endian
        val info = DataLine.Info(TargetDataLine::class.java, format)
        val recorder = linuxRecorderCommand()
        if (recorder == null && !AudioSystem.isLineSupported(info)) {
            logger("no microphone available — dictation disabled")
            return
        }
        logger(
            if (recorder != null) "capturing via recorder: ${recorder.first()}"
            else "capturing via javax.sound TargetDataLine",
        )
        try {
            // Silence Vosk's native model-load LOG spam in production; keep it on a
            // debug/internal run (`-Dlocalagent.debug=true`). The native lib logs to
            // stderr directly, so this is the only lever (DesktopDiag can't gate it).
            LibVosk.setLogLevel(if (DesktopDiag.verbose) LogLevel.INFO else LogLevel.WARNINGS)
            Model(modelPath).use { model ->
                Recognizer(model, SAMPLE_RATE).use { recognizer ->
                    var consecutiveBarren = 0
                    while (coroutineContext.isActive) {
                        val end = runCaptureSession(info, format, recognizer, recorder)
                        if (end == SessionEnd.Cancelled || !coroutineContext.isActive) break
                        // Stale: the source died (suspend/resume) or the watchdog force-closed
                        // it. A session that read audio is a normal recovery (reset the guard);
                        // one that produced nothing counts toward giving up so a gone device
                        // can't respawn forever.
                        consecutiveBarren = if (lastSessionProducedAudio) 0 else consecutiveBarren + 1
                        if (consecutiveBarren >= MAX_BARREN_SESSIONS) {
                            logger("microphone produced no audio after $consecutiveBarren attempts — dictation disabled")
                            break
                        }
                        val backoff = backoffMs(consecutiveBarren - 1)
                        logger("microphone source went stale — reopening in ${backoff}ms")
                        delay(backoff)
                        recognizer.reset()
                    }
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            logger("Vosk capture failed: ${t.message}")
        } finally {
            currentSource?.let { runCatching { it.close() } }
            currentSource = null
            _isListening.value = false
        }
    }

    /**
     * Opens one mic source and reads until the coroutine is cancelled ([SessionEnd.Cancelled])
     * or the source goes stale ([SessionEnd.Stale]). The source is closed before returning so
     * the outer loop can cleanly reopen.
     */
    private suspend fun runCaptureSession(
        info: DataLine.Info,
        format: AudioFormat,
        recognizer: Recognizer,
        recorder: List<String>?,
    ): SessionEnd {
        lastSessionProducedAudio = false
        val source: MicSource = try {
            openSource(info, format, recorder)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            currentSource = null
            _isListening.value = false
            logger("failed to open microphone: ${t.message}")
            return SessionEnd.Stale
        }
        currentSource = source
        lastAudioAtMs = System.currentTimeMillis()
        _isListening.value = true
        logger("microphone open — dictation listening")
        val buffer = ByteArray(BUFFER_BYTES)
        var lastPartial = ""
        var consecutiveZero = 0
        var carry = 0 // bytes held from a previous read (0/1) to keep 16-bit frames aligned
        try {
            while (coroutineContext.isActive) {
                val read = try {
                    source.read(buffer, carry)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    // A wedged source (incl. the watchdog's close()) surfaces here.
                    logger("microphone read threw (${t.message}) — treating source as stale")
                    return SessionEnd.Stale
                }
                when (classifyRead(read, consecutiveZero)) {
                    ReadOutcome.Stale -> {
                        logger("microphone read returned $read (consecutiveZero=$consecutiveZero) — treating source as stale")
                        return SessionEnd.Stale
                    }
                    ReadOutcome.KeepWaiting -> {
                        consecutiveZero++
                        DesktopDiag.log("[Dictation] empty read ($consecutiveZero/$MAX_CONSECUTIVE_ZERO)")
                        delay(EMPTY_READ_PAUSE_MS) // avoid a hot spin while momentarily empty
                        continue
                    }
                    ReadOutcome.Data -> {
                        consecutiveZero = 0
                        lastAudioAtMs = System.currentTimeMillis()
                        lastSessionProducedAudio = true
                    }
                }
                // Feed only whole 16-bit frames; carry a trailing odd byte to the next read
                // (recorder pipes can split mid-sample, unlike a frame-aligned TargetDataLine).
                val total = carry + read
                val even = total and 1.inv()
                if (even > 0) {
                    if (recognizer.acceptWaveForm(buffer, even)) {
                        extractText(recognizer.result)?.let { _results.tryEmit(it) }
                        lastPartial = ""
                    } else {
                        // PR #67 — stream the in-progress transcript so words appear in the
                        // prompt box while talking. Vosk grows the `partial` field each frame;
                        // emit only on change to avoid spamming identical strings.
                        val partial = extractPartial(recognizer.partialResult)
                        if (partial != null && partial != lastPartial) {
                            lastPartial = partial
                            _partials.tryEmit(partial)
                        }
                    }
                }
                carry = total - even
                if (carry == 1) buffer[0] = buffer[even]
            }
            return SessionEnd.Cancelled
        } finally {
            // Drop _isListening so the mic button reflects the gap; it flips back true when
            // the next session opens. Don't null `currentSource` if the watchdog swapped it.
            _isListening.value = false
            runCatching { source.close() }
            if (currentSource === source) currentSource = null
        }
    }

    /** Opens the platform capture source: recorder subprocess (Linux) or [TargetDataLine]. */
    private fun openSource(info: DataLine.Info, format: AudioFormat, recorder: List<String>?): MicSource {
        if (recorder != null) {
            val proc = ProcessBuilder(recorder).redirectErrorStream(false).start()
            // Drain the recorder's stderr so its pipe can never fill and block capture.
            scope.launch {
                runCatching { proc.errorStream.bufferedReader().forEachLine { DesktopDiag.log("[Dictation] ${recorder.first()}: $it") } }
            }
            DesktopDiag.log("[Dictation] recorder ${recorder.first()} started (pid ${proc.pid()})")
            return ProcessMicSource(proc)
        }
        val line = (AudioSystem.getLine(info) as TargetDataLine).apply {
            open(format)
            start()
        }
        DesktopDiag.log("[Dictation] line open (${format.sampleRate.toInt()}Hz, buffer ${BUFFER_BYTES}B)")
        return LineMicSource(line)
    }

    /**
     * Watchdog for a source wedged with no return value (Linux suspend/resume can leave a
     * read blocked). If no audio has arrived for [STALL_TIMEOUT_MS] while we believe we're
     * listening, force-close the source — that unblocks the read, which [runCaptureSession]
     * then classifies as stale and recovers.
     */
    private suspend fun stallWatchdog() {
        while (coroutineContext.isActive) {
            delay(WATCHDOG_TICK_MS)
            if (!_isListening.value) continue
            val idle = System.currentTimeMillis() - lastAudioAtMs
            if (idle > STALL_TIMEOUT_MS) {
                val stale = currentSource
                if (stale != null) {
                    logger("no audio for ${idle}ms — source appears stale, forcing reopen")
                    runCatching { stale.close() }
                    // Reset so we don't re-fire every tick before the reopen lands.
                    lastAudioAtMs = System.currentTimeMillis()
                }
            }
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

    /** Detects an available Linux recorder CLI; null on macOS/Windows or when none is found. */
    private fun linuxRecorderCommand(): List<String>? {
        if (!DesktopOs.isLinux) return null
        return RECORDERS.firstOrNull { which(it.first()) }
    }

    /** A capture source the read loop pulls raw 16-bit PCM from. */
    private interface MicSource {
        /** Reads PCM into [buffer] starting at [offset]; returns bytes read, or -1 at EOF. */
        fun read(buffer: ByteArray, offset: Int): Int
        fun close()
    }

    private class LineMicSource(private val line: TargetDataLine) : MicSource {
        override fun read(buffer: ByteArray, offset: Int): Int = line.read(buffer, offset, buffer.size - offset)
        override fun close() {
            runCatching { line.stop(); line.close() }
        }
    }

    private class ProcessMicSource(private val proc: Process) : MicSource {
        private val input = proc.inputStream
        override fun read(buffer: ByteArray, offset: Int): Int = input.read(buffer, offset, buffer.size - offset)
        override fun close() {
            runCatching { proc.destroyForcibly() }
            runCatching { input.close() }
        }
    }

    /** Why a single [runCaptureSession] ended. */
    private enum class SessionEnd { Cancelled, Stale }

    /** Classification of one source read return value. */
    internal enum class ReadOutcome { Data, KeepWaiting, Stale }

    companion object {
        private const val SAMPLE_RATE = 16_000f
        private const val BUFFER_BYTES = 4096

        /**
         * Linux recorder CLIs in preference order, each emitting raw S16LE/16 kHz/mono to
         * stdout. `parec` covers PulseAudio AND PipeWire (via pipewire-pulse); `arecord` is
         * the ALSA fallback.
         */
        private val RECORDERS: List<List<String>> = listOf(
            listOf("parec", "--format=s16le", "--rate=16000", "--channels=1"),
            listOf("arecord", "-q", "-f", "S16_LE", "-r", "16000", "-c", "1", "-t", "raw"),
        )

        /** A run of empty reads longer than this means the line is dead, not just quiet. */
        internal const val MAX_CONSECUTIVE_ZERO = 100

        private const val EMPTY_READ_PAUSE_MS = 20L
        private const val WATCHDOG_TICK_MS = 1_000L
        private const val STALL_TIMEOUT_MS = 3_000L
        private const val MAX_BARREN_SESSIONS = 5

        /** True when `which <bin>` exits 0 — i.e. the recorder is on PATH. */
        private fun which(bin: String): Boolean = runCatching {
            ProcessBuilder("which", bin).redirectErrorStream(true).start().let { p ->
                p.inputStream.readBytes() // drain so the short pipe can't block waitFor
                p.waitFor() == 0
            }
        }.getOrDefault(false)

        /**
         * Pure classification of a source read result — extracted so it can be unit-tested
         * without audio hardware (mirrors the `LinuxNotificationPresenter.buildArgv` pattern).
         * `-1` is EOF/closed (recorder died, or the suspend/resume line signature); a long run
         * of `0`s means the line stopped delivering; anything `> 0` is real audio.
         */
        internal fun classifyRead(read: Int, consecutiveZero: Int): ReadOutcome = when {
            read > 0 -> ReadOutcome.Data
            read < 0 -> ReadOutcome.Stale
            consecutiveZero >= MAX_CONSECUTIVE_ZERO -> ReadOutcome.Stale
            else -> ReadOutcome.KeepWaiting
        }

        /** Backoff before a reopen attempt: 300 ms, doubling, capped at 3 s. */
        internal fun backoffMs(consecutiveFailures: Int): Long {
            val base = 300L shl consecutiveFailures.coerceIn(0, 4)
            return base.coerceAtMost(3_000L)
        }
    }
}
