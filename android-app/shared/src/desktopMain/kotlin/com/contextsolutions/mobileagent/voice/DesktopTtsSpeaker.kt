package com.contextsolutions.mobileagent.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop [ChatSpeaker] (docs/DESKTOP_PORT_PLAN.md, Phase 7) — read-aloud via the
 * per-OS system speech engine, shelled out with [ProcessBuilder]:
 *  - macOS → `say`
 *  - Linux → `spd-say --wait` (speech-dispatcher; the common desktop TTS bridge)
 *  - Windows → PowerShell `System.Speech.Synthesis.SpeechSynthesizer`
 *
 * No bundled TTS engine + no network — consistent with the offline posture, and
 * the desktop analogue of Android's on-device `TextToSpeech`. If the OS command
 * is missing the call degrades to a no-op (logged), so a headless / engine-less
 * box doesn't error.
 *
 * [isSpeaking] flips true around the spawned process so continuous dictation can
 * suppress echo (#42); [speak] flushes any in-progress utterance first (mirroring
 * Android's `QUEUE_FLUSH`), and [stop] kills the current process.
 */
class DesktopTtsSpeaker(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val osName: String = System.getProperty("os.name").orEmpty().lowercase(),
    private val logger: (String) -> Unit = { System.err.println("[ChatSpeaker] $it") },
) : ChatSpeaker {

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var speakJob: Job? = null

    override fun speak(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        stop() // QUEUE_FLUSH semantics
        val command = commandFor(trimmed) ?: run {
            logger("no TTS engine for os='$osName' — read-aloud disabled")
            return
        }
        speakJob = scope.launch {
            try {
                val proc = ProcessBuilder(command).redirectErrorStream(true).start()
                process = proc
                _isSpeaking.value = true
                proc.waitFor()
            } catch (t: Throwable) {
                logger("TTS failed: ${t.message}")
            } finally {
                _isSpeaking.value = false
                process = null
            }
        }
    }

    override fun stop() {
        process?.destroy()
        process = null
        speakJob?.cancel()
        speakJob = null
        _isSpeaking.value = false
    }

    private fun commandFor(text: String): List<String>? = when {
        osName.contains("mac") || osName.contains("darwin") -> listOf("say", text)
        osName.contains("win") -> listOf(
            "powershell",
            "-NoProfile",
            "-Command",
            "Add-Type -AssemblyName System.Speech; " +
                "(New-Object System.Speech.Synthesis.SpeechSynthesizer).Speak('${escapePowerShell(text)}')",
        )
        // Linux/other: speech-dispatcher's spd-say (--wait blocks until done).
        else -> listOf("spd-say", "--wait", text)
    }

    /** PowerShell single-quoted strings escape a quote by doubling it. */
    private fun escapePowerShell(text: String): String = text.replace("'", "''")
}
