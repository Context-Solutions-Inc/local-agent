package com.contextsolutions.localagent.voice

/**
 * A selectable desktop read-aloud voice (PR #66). [id] is the value handed to the
 * OS speech engine; [label] is the human-facing name shown in Settings.
 *  - macOS → `say -v <id>` (id is the voice name from `say -v ?`)
 *  - Linux → `spd-say -y <id>` (id is the synthesis-voice NAME from `spd-say -L`)
 *  - Windows → `SpeechSynthesizer.SelectVoice(<id>)`
 */
data class DesktopVoice(val id: String, val label: String)

/**
 * The Linux-only "voice engine" — a speech-dispatcher output module (`spd-say -O`),
 * e.g. `espeak-ng` (the robotic default) or a better one the user installs (RHVoice,
 * pico, a piper bridge…). Empty on macOS/Windows, which have no module concept.
 */
data class DesktopVoiceEngine(val id: String, val label: String)

/**
 * Persisted desktop read-aloud voice settings (PR #66). Desktop-only — kept OFF the
 * shared [TtsPreferences] interface (mobile picks its voice via the OS), the same way
 * the desktop UI-zoom lives only on the concrete `DesktopThemePreferences` (#45).
 *
 * - [engine] — Linux speech-dispatcher output module (`spd-say -o`); blank = daemon default.
 * - [voice]  — synthesis voice id; blank = engine default.
 * - [rate]   — normalized speech rate in [-100, 100], 0 = engine default. Mapped per OS
 *   in [DesktopTtsSpeaker] (spd-say uses it directly; `say`/Windows are scaled).
 */
data class DesktopVoiceConfig(
    val engine: String = "",
    val voice: String = "",
    val rate: Int = 0,
) {
    companion object {
        const val RATE_MIN = -100
        const val RATE_MAX = 100

        /**
         * Sentinel [engine] value selecting the bundled Piper neural engine (PR #66)
         * instead of an OS speech-dispatcher module. When set, [voice] holds a Piper
         * voice id ([PiperVoices]) rather than a `spd-say` synthesis-voice name.
         */
        const val PIPER_ENGINE = "piper"
    }
}
