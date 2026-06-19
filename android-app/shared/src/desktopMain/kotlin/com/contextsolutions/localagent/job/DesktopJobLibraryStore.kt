package com.contextsolutions.localagent.job

import com.contextsolutions.localagent.inference.DesktopAppDirs
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Copies the bundled `agent-jobs` library into the per-user app-data dir (PR #100).
 *
 * The desktop build zips the `agent-jobs` git submodule into the classpath resource
 * `/agent-jobs.zip` (see `desktopApp/build.gradle.kts`). On startup [ensure] extracts
 * it to `<app-data>/agent-jobs` (e.g. `~/.local/share/LocalAgent/agent-jobs`) so the
 * Choose Job catalog ([DesktopJobCatalog]) has jobs to offer without a network fetch.
 *
 * The extract is **overlay**, gated by a `.deployment` stamp:
 *  - If the stamp already matches [deploymentId] (this app build), do nothing.
 *  - Otherwise extract every zip entry, OVERWRITING the bundled (repo-tracked) files
 *    but never deleting the directory — so user-generated files inside a job folder
 *    (`node_modules`, `.env`, `seen.json`, the per-job `.localagent-init.json` marker)
 *    survive a new deployment. Then write the stamp.
 *
 * Known limitation: an overlay never prunes a job folder removed upstream. Acceptable
 * for now (a stale extra job is harmless; the catalog is driven by `job.list.json`).
 *
 * Best-effort: any failure is caught by the caller (Main.kt wraps in `runCatching`);
 * a missing resource (e.g. a bare unit-test classpath) is a no-op.
 */
class DesktopJobLibraryStore(
    private val deploymentId: String,
    private val baseDir: File = DesktopAppDirs.dataDir(),
    private val resourceOpener: () -> InputStream? = {
        DesktopJobLibraryStore::class.java.getResourceAsStream(RESOURCE_PATH)
    },
    private val logger: (String) -> Unit = {},
) {
    /** `<app-data>/agent-jobs` — the extracted library root the catalog reads. */
    fun dir(): File = File(baseDir, DIR_NAME)

    private fun stampFile(): File = File(dir(), STAMP_NAME)

    /**
     * Extract the bundled library if the on-disk copy is missing or from a different
     * deployment. Returns true if an extract ran, false if it was already current or
     * the bundled resource is absent.
     */
    fun ensure(): Boolean {
        val target = dir()
        val current = stampFile().takeIf { it.isFile }?.readText()?.trim()
        if (current == deploymentId && target.isDirectory) {
            logger("agent-jobs library up to date ($deploymentId)")
            return false
        }
        val stream = resourceOpener()
        if (stream == null) {
            logger("agent-jobs.zip resource absent — skipping extract")
            return false
        }
        target.mkdirs()
        var entries = 0
        stream.use { raw ->
            ZipInputStream(raw).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val outFile = resolveSafely(target, entry.name)
                    if (outFile == null) {
                        logger("skipped unsafe zip entry: ${entry.name}")
                        zip.closeEntry()
                        continue
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { zip.copyTo(it) }
                        entries++
                    }
                    zip.closeEntry()
                }
            }
        }
        markExecutables(target)
        stampFile().writeText(deploymentId)
        logger("extracted agent-jobs library ($entries files) → ${target.path} [$deploymentId]")
        return true
    }

    /**
     * `java.util.zip` drops the Unix executable bit, so a freshly extracted `watch.sh`
     * (or any `program[os]` entry) lands non-executable and the job fails with "permission
     * denied" on Linux/macOS. Restore it: walk the tree and `chmod +x` every `job.settings.json`'s
     * resolved `program` files plus any `*.sh` (the documented launcher convention). No-op on
     * Windows (`setExecutable` is meaningless there). Best-effort per file.
     *
     * Owner-only (`setExecutable(true, true)`, security fix F3): the extracted job files live in
     * the per-user app-data dir, so the exec bit shouldn't be world-wide.
     */
    private fun markExecutables(root: File) {
        if (isWindows) return
        root.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            when {
                file.name == JobSettingsLoader.FILE_NAME ->
                    JobSettingsLoader.load(file)?.program?.values?.forEach { rel ->
                        if (rel.isNotBlank()) runCatching { File(file.parentFile, rel).setExecutable(true, true) }
                    }
                file.name.endsWith(".sh") -> runCatching { file.setExecutable(true, true) }
            }
        }
    }

    /**
     * Resolve [name] under [root], guarding against Zip-Slip (entries that escape the
     * target via `../`). Returns null when the entry would land outside [root].
     */
    private fun resolveSafely(root: File, name: String): File? {
        val out = File(root, name)
        val rootPath = root.canonicalFile.path + File.separator
        val outPath = out.canonicalFile.path
        return if (outPath == root.canonicalFile.path || outPath.startsWith(rootPath)) out else null
    }

    private val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    companion object {
        const val DIR_NAME = "agent-jobs"
        const val RESOURCE_PATH = "/agent-jobs.zip"
        const val STAMP_NAME = ".deployment"
    }
}
