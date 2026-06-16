package com.contextsolutions.localagent.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * Desktop [ChatSpeaker] (docs/DESKTOP_PORT_PLAN.md, Phase 7) — read-aloud via either the
 * bundled **Piper** neural engine (when `voiceConfig().engine == PIPER_ENGINE`, PR #66) or
 * the per-OS system speech engine, shelled out with [ProcessBuilder]:
 *  - macOS → `say`
 *  - Linux → `spd-say --wait` (speech-dispatcher; the common desktop TTS bridge)
 *  - Windows → PowerShell `System.Speech.Synthesis.SpeechSynthesizer`
 *
 * No bundled TTS engine + no network for the OS path — consistent with the offline posture,
 * and the desktop analogue of Android's on-device `TextToSpeech`. If the OS command is
 * missing the call degrades to a no-op (logged). Voice/engine/rate come from [voiceConfig],
 * read fresh on every [speak] so a Settings change applies to the next utterance.
 *
 * **Stopping is engine-specific (invariant #48):**
 *  - **Piper** plays in-JVM, so [PiperSpeechSynthesizer.stop] silences it instantly.
 *  - **macOS `say` / Windows synth** play in-process — `process.destroy()` suffices.
 *  - **Linux `spd-say`** is only a *client*; the speech-dispatcher daemon keeps playing after
 *    the client dies, so [stop] also issues `spd-say --cancel`.
 */
class DesktopTtsSpeaker(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val osName: String = System.getProperty("os.name").orEmpty().lowercase(),
    private val voiceConfig: () -> DesktopVoiceConfig = { DesktopVoiceConfig() },
    private val piper: PiperSpeechSynthesizer? = null,
    private val logger: (String) -> Unit = { System.err.println("[ChatSpeaker] $it") },
) : ChatSpeaker {

    private val _shellSpeaking = MutableStateFlow(false)

    // Speaking = shell engine OR Piper. Echo suppression (#42) needs the real playback state.
    override val isSpeaking: StateFlow<Boolean> =
        combine(_shellSpeaking, piper?.isSpeaking ?: flowOf(false)) { shell, neural -> shell || neural }
            .stateIn(scope, SharingStarted.Eagerly, false)

    private val isMac get() = osName.contains("mac") || osName.contains("darwin")
    private val isWindows get() = osName.contains("win")

    @Volatile
    private var process: Process? = null

    @Volatile
    private var speakJob: Job? = null

    override fun speak(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val cfg = voiceConfig()
        if (cfg.engine == DesktopVoiceConfig.PIPER_ENGINE && piper?.isAvailable() == true) {
            stopShell()
            piper.speak(trimmed, cfg.voice, cfg.rate)
            return
        }
        stop() // QUEUE_FLUSH semantics
        val command = commandFor(trimmed, cfg) ?: run {
            logger("no TTS engine for os='$osName' — read-aloud disabled")
            return
        }
        speakJob = scope.launch {
            try {
                val proc = ProcessBuilder(command).redirectErrorStream(true).start()
                process = proc
                _shellSpeaking.value = true
                proc.waitFor()
            } catch (t: Throwable) {
                logger("TTS failed: ${t.message}")
            } finally {
                _shellSpeaking.value = false
                process = null
            }
        }
    }

    override fun stop() {
        piper?.stop()
        stopShell()
    }

    private fun stopShell() {
        process?.destroy()
        process = null
        speakJob?.cancel()
        speakJob = null
        _shellSpeaking.value = false
        // Linux: the speech-dispatcher daemon outlives the killed client, so cancel
        // its queue explicitly. Fire-and-forget; failure (no engine) is a no-op.
        if (!isMac && !isWindows) {
            try {
                ProcessBuilder("spd-say", "--cancel").start()
            } catch (_: Throwable) {
                // no speech-dispatcher — nothing was playing anyway
            }
        }
    }

    internal fun commandFor(text: String, cfg: DesktopVoiceConfig): List<String>? = when {
        isMac -> buildList {
            add("say")
            if (cfg.voice.isNotBlank()) { add("-v"); add(cfg.voice) }
            // `say -r` is words-per-minute (~175 default). Map [-100,100] → ~[75,275].
            if (cfg.rate != 0) { add("-r"); add((175 + cfg.rate).coerceIn(60, 360).toString()) }
            add(text)
        }
        isWindows -> {
            // .Rate is [-10,10]; scale the normalized [-100,100] rate down.
            val rateExpr = if (cfg.rate != 0) "\$s.Rate = ${(cfg.rate / 10).coerceIn(-10, 10)}; " else ""
            val voiceExpr = if (cfg.voice.isNotBlank()) "\$s.SelectVoice('${escapePowerShell(cfg.voice)}'); " else ""
            listOf(
                "powershell",
                "-NoProfile",
                "-Command",
                "Add-Type -AssemblyName System.Speech; " +
                    "\$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
                    voiceExpr + rateExpr +
                    "\$s.Speak('${escapePowerShell(text)}')",
            )
        }
        // Linux/other: speech-dispatcher's spd-say (--wait blocks until done).
        else -> buildList {
            add("spd-say")
            add("--wait")
            if (cfg.engine.isNotBlank() && cfg.engine != DesktopVoiceConfig.PIPER_ENGINE) { add("-o"); add(cfg.engine) }
            if (cfg.voice.isNotBlank()) { add("-y"); add(cfg.voice) }
            // spd-say -r takes [-100,100] directly.
            if (cfg.rate != 0) { add("-r"); add(cfg.rate.coerceIn(-100, 100).toString()) }
            add(text)
        }
    }

    /** PowerShell single-quoted strings escape a quote by doubling it. */
    private fun escapePowerShell(text: String): String = text.replace("'", "''")
}
