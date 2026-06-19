package com.contextsolutions.localagent.job

import com.contextsolutions.localagent.inference.DesktopAppDirs
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Node runtime a job uses: a private install dir, or the system one (binDir == null). */
data class NodeRuntime(val binDir: File?)

/** Outcome of [DesktopNodeProvisioner.ensure]. */
sealed interface NodeResult {
    data class Available(val runtime: NodeRuntime) : NodeResult
    data class Failed(val reason: String) : NodeResult
}

/**
 * Ensures Node.js + npm are available for a job (PR #100). A job manifest opts in via
 * `init.requires: ["node"]`.
 *
 * Resolution order:
 *  1. A previously provisioned private Node under `<app-data>/runtimes/node` → use it.
 *  2. The system `node` + `npm` (both on `PATH`) → use them ([NodeRuntime.binDir] == null).
 *  3. Otherwise download the pinned Node LTS for this OS/arch and extract it to
 *     `<app-data>/runtimes/node` (a stable dir, so [DesktopJobRuntimeEnv] can add it to the
 *     job subprocess `PATH` both during init and at run time).
 *
 * The downloaded archive is verified against a per-asset SHA-256 pinned in [NODE_SHA256]
 * BEFORE it is extracted/executed, so a compromised mirror or MITM cannot deliver an
 * arbitrary binary (the resulting `node` is later put on every job subprocess' PATH). The
 * dist mirror can be overridden with `LOCALAGENT_NODE_DIST_BASE` (e.g. an offline test
 * mirror), but only `https://` or a loopback (`127.0.0.1`/`localhost`) base is accepted —
 * the sha pin is the primary control and protects regardless of scheme.
 */
class DesktopNodeProvisioner(
    private val baseDir: File = DesktopAppDirs.dataDir(),
    private val distBase: String = System.getenv(ENV_DIST_BASE)?.takeIf { it.isNotBlank() } ?: DEFAULT_DIST_BASE,
    private val logger: (String) -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Test seam: override the system-Node probe to force the download path. */
    private val systemNodeProbe: (() -> Boolean)? = null,
) {

    suspend fun ensure(onProgress: (String) -> Unit): NodeResult = withContext(ioDispatcher) {
        DesktopJobRuntimeEnv.nodeBinDir(baseDir)?.let {
            logger("using previously provisioned Node at ${it.path}")
            return@withContext NodeResult.Available(NodeRuntime(it))
        }
        onProgress("Checking for Node.js…")
        if (systemNodeProbe?.invoke() ?: systemNodeAndNpm()) {
            logger("system Node.js + npm found on PATH")
            return@withContext NodeResult.Available(NodeRuntime(null))
        }
        val asset = assetForHost()
            ?: return@withContext NodeResult.Failed("No Node.js build is available for this operating system / architecture.")
        val expectedSha = NODE_SHA256[asset]
            ?: return@withContext NodeResult.Failed("No pinned checksum for Node.js asset $asset.")
        if (!isSafeDistBase(distBase)) {
            return@withContext NodeResult.Failed(
                "Refusing to download Node.js over an insecure mirror ($distBase); use https:// or a loopback test mirror.",
            )
        }

        val tmp = File(baseDir, "runtimes/.node-download").apply { mkdirs() }
        val archive = File(tmp, asset)
        try {
            onProgress("Downloading Node.js…")
            val url = "$distBase/$NODE_VERSION/$asset"
            logger("downloading Node.js from $url")
            download(url, archive, expectedSha)

            onProgress("Installing Node.js…")
            if (DesktopToolPreflight.resolveExecutable("tar") == null) {
                return@withContext NodeResult.Failed(DesktopToolPreflight.friendlyToolMessage("tar"))
            }
            val extractDir = File(tmp, "extract").apply { deleteRecursively(); mkdirs() }
            extract(archive, extractDir)
            val versioned = extractDir.listFiles()?.firstOrNull { it.isDirectory && it.name.startsWith("node-") }
                ?: return@withContext NodeResult.Failed("Node.js archive had an unexpected layout.")

            val dest = DesktopJobRuntimeEnv.nodeRoot(baseDir)
            dest.deleteRecursively()
            dest.parentFile?.mkdirs()
            if (!versioned.renameTo(dest)) {
                versioned.copyRecursively(dest, overwrite = true)
                versioned.deleteRecursively()
            }
            val binDir = DesktopJobRuntimeEnv.nodeBinDir(baseDir)
                ?: return@withContext NodeResult.Failed("Node.js installed but its `node` binary wasn't found.")
            if (!verify(binDir)) {
                return@withContext NodeResult.Failed("The downloaded Node.js failed to run on this machine.")
            }
            logger("provisioned private Node.js at ${binDir.path}")
            NodeResult.Available(NodeRuntime(binDir))
        } catch (t: Throwable) {
            NodeResult.Failed(friendlyDownloadError(t))
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun systemNodeAndNpm(): Boolean = runQuiet("node --version") && runQuiet("npm --version")

    private fun verify(binDir: File): Boolean = runCatching {
        val node = File(binDir, if (isWindows) "node.exe" else "node")
        val proc = ProcessBuilder(node.absolutePath, "--version").redirectErrorStream(true).start()
        val ok = proc.waitFor(30, TimeUnit.SECONDS) && proc.exitValue() == 0
        if (!ok) proc.destroyForcibly()
        ok
    }.getOrDefault(false)

    /** Run a shell command quietly; true iff it exits 0 within a short timeout. */
    private fun runQuiet(command: String): Boolean = runCatching {
        val argv = if (isWindows) listOf("cmd", "/c", command) else listOf("sh", "-c", command)
        val proc = ProcessBuilder(argv).redirectErrorStream(true).start()
        proc.inputStream.readBytes() // drain so the pipe never blocks
        val finished = proc.waitFor(20, TimeUnit.SECONDS)
        if (!finished) { proc.destroyForcibly(); false } else proc.exitValue() == 0
    }.getOrDefault(false)

    private fun download(url: String, dest: File, expectedSha256: String) {
        val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        conn.inputStream.use { input ->
            dest.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    digest.update(buf, 0, n)
                }
            }
        }
        check(dest.length() > 0L) { "downloaded Node.js archive is empty" }
        // Verify integrity BEFORE the archive is ever extracted/executed.
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            dest.delete()
            throw NodeChecksumException(
                "Node.js checksum mismatch for ${dest.name} (expected $expectedSha256, got $actual)",
            )
        }
    }

    /** Extract via the system `tar` (GNU on Linux, bsdtar on macOS/Windows 10+ — handles gz + zip). */
    private fun extract(archive: File, targetDir: File) {
        targetDir.mkdirs()
        val proc = ProcessBuilder("tar", "-xf", archive.absolutePath, "-C", targetDir.absolutePath)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        check(code == 0) { "tar extract failed (exit=$code): ${out.take(500)}" }
    }

    /** Translate common network failures into actionable copy; fall back to the raw message. */
    private fun friendlyDownloadError(t: Throwable): String = when (t) {
        is NodeChecksumException ->
            "Node.js download failed an integrity check and was discarded. Try again; if it persists, your network may be tampering with the download."
        is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException ->
            "Couldn't download Node.js — check your internet connection and try again."
        else -> "Couldn't install Node.js: ${t.message}"
    }

    /** Raised when the downloaded archive's SHA-256 doesn't match the pinned value. */
    private class NodeChecksumException(message: String) : Exception(message)

    private fun assetForHost(): String? {
        val arch = when (System.getProperty("os.arch").orEmpty().lowercase()) {
            "amd64", "x86_64", "x64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            else -> return null
        }
        return when {
            isWindows -> if (arch == "x64") "node-$NODE_VERSION-win-x64.zip" else null
            isMac -> "node-$NODE_VERSION-darwin-$arch.tar.gz"
            else -> "node-$NODE_VERSION-linux-$arch.tar.gz"
        }
    }

    private val isWindows: Boolean get() = osName.contains("win")
    private val isMac: Boolean get() = osName.contains("mac") || osName.contains("darwin")
    private val osName: String get() = System.getProperty("os.name").orEmpty().lowercase()

    companion object {
        const val NODE_VERSION = "v20.18.1"
        const val ENV_DIST_BASE = "LOCALAGENT_NODE_DIST_BASE"
        const val DEFAULT_DIST_BASE = "https://nodejs.org/dist"

        /**
         * Pinned SHA-256 per supported asset for [NODE_VERSION], from the official
         * `https://nodejs.org/dist/v20.18.1/SHASUMS256.txt`. Bump these together with
         * [NODE_VERSION]. Keys must match [assetForHost]'s output exactly.
         */
        internal val NODE_SHA256: Map<String, String> = mapOf(
            "node-v20.18.1-linux-x64.tar.gz" to "259e5a8bf2e15ecece65bd2a47153262eda71c0b2c9700d5e703ce4951572784",
            "node-v20.18.1-linux-arm64.tar.gz" to "73cd297378572e0bc9dfc187c5ec8cca8d43aee6a596c10ebea1ed5f9ec682b6",
            "node-v20.18.1-darwin-x64.tar.gz" to "c5497dd17c8875b53712edaf99052f961013cedc203964583fc0cfc0aaf93581",
            "node-v20.18.1-darwin-arm64.tar.gz" to "9e92ce1032455a9cc419fe71e908b27ae477799371b45a0844eedb02279922a4",
            "node-v20.18.1-win-x64.zip" to "56e5aacdeee7168871721b75819ccacf2367de8761b78eaceacdecd41e04ca03",
        )

        /** Accept only an `https://` mirror or a loopback test mirror; reject other cleartext. */
        internal fun isSafeDistBase(base: String): Boolean {
            val lower = base.trim().lowercase()
            return lower.startsWith("https://") ||
                lower.startsWith("http://127.0.0.1") ||
                lower.startsWith("http://localhost") ||
                lower.startsWith("http://[::1]")
        }
    }
}
