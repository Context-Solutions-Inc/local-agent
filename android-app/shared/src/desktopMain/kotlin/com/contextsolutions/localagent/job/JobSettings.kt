package com.contextsolutions.localagent.job

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * A `job.settings.json` manifest that lives inside a job's program folder (PR #86).
 * It lets the user pick a *folder* rather than guess the right OS-specific entry
 * point, and it carries hidden CLI args plus program-defined settings:
 *
 * ```json
 * {
 *   "version": 1,
 *   "name": "Property Search (Canada)",
 *   "program": { "linux": "watch.sh", "macos": "watch.sh", "windows": "watch.cmd" },
 *   "args": ["--headless"],
 *   "settings": { "...": "program-defined, read by the program from its own cwd" }
 * }
 * ```
 *
 * - [program] maps an OS key (`linux`/`macos`/`windows`) → the entry point,
 *   resolved relative to the manifest's folder (an absolute path also works).
 *   The desktop picker reads this to fill the "Job Location" field.
 * - [args] are passed to the program on every run but never shown in the UI and
 *   never collide with the user-typed keyword(s); [JobExecutor] reads them live.
 * - [settings] is opaque to us — the program reads it from its own working
 *   directory. We deliberately don't interpret it.
 */
@Serializable
data class JobSettings(
    val version: Int = 1,
    val name: String? = null,
    val description: String? = null,
    val program: Map<String, String> = emptyMap(),
    val args: List<String> = emptyList(),
    val settings: JsonElement? = null,
)

object JobSettingsLoader {
    const val FILE_NAME = "job.settings.json"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Load the manifest. [path] may be the job folder OR the `job.settings.json`
     * file itself; returns null if the file is absent or unparseable.
     */
    fun load(path: File): JobSettings? = runCatching {
        val file = if (path.isDirectory) File(path, FILE_NAME) else path
        if (file.isFile) json.decodeFromString<JobSettings>(file.readText()) else null
    }.getOrNull()

    /** True iff [dir] is a directory containing a `job.settings.json` file. */
    fun hasManifest(dir: File): Boolean = dir.isDirectory && File(dir, FILE_NAME).isFile

    /**
     * Resolve the program entry point for the current OS, relative to [jobDir].
     * Returns null when there's no entry for this OS. `File(jobDir, rel)` returns
     * the absolute path unchanged if the manifest value is already absolute.
     */
    fun resolveProgram(settings: JobSettings, jobDir: File): File? =
        currentOsKey()
            ?.let { settings.program[it] }
            ?.takeIf { it.isNotBlank() }
            ?.let { File(jobDir, it) }

    /** `linux` / `macos` / `windows`, or null on an unrecognized OS. */
    fun currentOsKey(): String? {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("win") -> "windows"
            os.contains("mac") || os.contains("darwin") -> "macos"
            os.contains("nux") || os.contains("nix") || os.contains("aix") -> "linux"
            else -> null
        }
    }
}
