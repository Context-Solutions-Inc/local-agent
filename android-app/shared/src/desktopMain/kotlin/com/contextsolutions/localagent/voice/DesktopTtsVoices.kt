package com.contextsolutions.localagent.voice

import java.util.concurrent.TimeUnit

/**
 * Enumerates the desktop read-aloud voices (and, on Linux, output-module "engines")
 * available from the per-OS system speech engine (PR #66). Shells out the same way
 * [DesktopTtsSpeaker] does:
 *  - macOS → `say -v ?`            → voices (no engines)
 *  - Linux → `spd-say -O` / `-L`   → engines (output modules) + synthesis voices
 *  - Windows → PowerShell `GetInstalledVoices()` → voices (no engines)
 *
 * Every query is best-effort: a missing engine, an unexpected format, or a timeout
 * yields an empty list (Settings then offers only "System default"), never an error.
 */
class DesktopTtsVoices(
    private val osName: String = System.getProperty("os.name").orEmpty().lowercase(),
    private val runCommand: (List<String>) -> String? = ::defaultRun,
) {

    private val isMac get() = osName.contains("mac") || osName.contains("darwin")
    private val isWindows get() = osName.contains("win")

    /** Linux output modules; empty on macOS/Windows (no module concept). */
    fun engines(): List<DesktopVoiceEngine> = when {
        isMac || isWindows -> emptyList()
        else -> parseSpdModules(runCommand(listOf("spd-say", "-O")))
    }

    /** Selectable synthesis voices for the current OS (may be large on espeak-ng). */
    fun voices(): List<DesktopVoice> = when {
        isMac -> parseMacVoices(runCommand(listOf("say", "-v", "?")))
        isWindows -> parseWindowsVoices(
            runCommand(
                listOf(
                    "powershell", "-NoProfile", "-Command",
                    "Add-Type -AssemblyName System.Speech; " +
                        "(New-Object System.Speech.Synthesis.SpeechSynthesizer)" +
                        ".GetInstalledVoices() | ForEach-Object { \$_.VoiceInfo.Name }",
                ),
            ),
        )
        else -> parseSpdVoices(runCommand(listOf("spd-say", "-L")))
    }

    // -- parsers (internal for tests) --

    internal fun parseSpdModules(out: String?): List<DesktopVoiceEngine> =
        out.orEmpty().lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("OUTPUT MODULES", ignoreCase = true) }
            .map { DesktopVoiceEngine(id = it, label = it) }
            .toList()

    internal fun parseSpdVoices(out: String?): List<DesktopVoice> =
        out.orEmpty().lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val cols = line.split(Regex("\\s+"))
                val name = cols.getOrNull(0) ?: return@mapNotNull null
                // Skip the "NAME LANGUAGE VARIANT" header.
                if (name.equals("NAME", ignoreCase = true)) return@mapNotNull null
                val lang = cols.getOrNull(1).orEmpty()
                val label = if (lang.isNotEmpty() && !lang.equals("none", ignoreCase = true)) {
                    "$name ($lang)"
                } else {
                    name
                }
                DesktopVoice(id = name, label = label)
            }
            .distinctBy { it.id }
            .toList()

    /** `say -v ?` lines: `<Name>  <lang_REGION>  # sample sentence`. */
    internal fun parseMacVoices(out: String?): List<DesktopVoice> =
        out.orEmpty().lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val beforeHash = line.substringBefore('#').trim()
                if (beforeHash.isEmpty()) return@mapNotNull null
                val tokens = beforeHash.split(Regex("\\s+"))
                val lang = tokens.last()
                // The name may contain spaces (e.g. "Bad News"); it's everything but the trailing lang code.
                val name = tokens.dropLast(1).joinToString(" ").ifEmpty { tokens.first() }
                DesktopVoice(id = name, label = "$name ($lang)")
            }
            .distinctBy { it.id }
            .toList()

    internal fun parseWindowsVoices(out: String?): List<DesktopVoice> =
        out.orEmpty().lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { DesktopVoice(id = it, label = it) }
            .distinctBy { it.id }
            .toList()

    private companion object {
        fun defaultRun(command: List<String>): String? = try {
            val proc = ProcessBuilder(command).redirectErrorStream(true).start()
            val text = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                proc.destroy()
                null
            } else {
                text
            }
        } catch (_: Throwable) {
            null
        }
    }
}
