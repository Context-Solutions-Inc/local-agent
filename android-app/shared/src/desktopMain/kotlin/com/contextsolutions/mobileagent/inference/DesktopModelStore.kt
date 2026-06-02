package com.contextsolutions.mobileagent.inference

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Desktop GGUF model acquisition (docs/DESKTOP_PORT_PLAN.md, Phase 4). The Android side
 * (`ModelInventory` + `ModelDownloader`, OkHttp/StatFs/Context-coupled) downloads the
 * `.litertlm` artifact; this is the JVM-desktop counterpart for a GGUF Gemma. Kept
 * self-contained in `desktopMain` (no shared types lifted, so `:androidApp` is untouched)
 * — a later refactor can unify the vocabulary into `commonMain` if both grow.
 *
 * Same guarantees as Android: resumable (`.partial` appended across runs, digest re-fed on
 * resume), atomic rename on completion (readers only ever see a verified file), SHA-256 +
 * size verification, a free-space pre-check, and cancellation between chunks.
 *
 * HTTP uses the JDK's [HttpURLConnection] rather than Ktor: the download is a long-lived
 * binary stream with Range/resume semantics, and HttpURLConnection's API is stable across
 * JVMs (Ktor's streaming surface is version-sensitive). The Brave/search path keeps Ktor.
 */

/** Immutable description of one desktop model artifact. Mirrors Android's `ModelSpec`. */
data class DesktopModelSpec(
    val filename: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
) {
    /** True once URL + checksum + size are all filled in (see [DesktopModelInventory.DEFAULT]). */
    val isConfigured: Boolean = downloadUrl.isNotBlank() && sha256.isNotBlank() && sizeBytes > 0L
}

/**
 * Resolves the OS-appropriate per-user application data directory. Big artifacts (GGUF) are
 * downloaded here at first run — too large to bundle, same policy as Android (PHASE plan §6).
 */
object DesktopAppDirs {
    fun dataDir(appName: String = "MobileAgent"): File {
        val home = System.getProperty("user.home").orEmpty()
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val base = when {
            os.contains("mac") || os.contains("darwin") -> File(home, "Library/Application Support")
            os.contains("win") -> File(System.getenv("LOCALAPPDATA") ?: "$home\\AppData\\Local")
            else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share") // Linux/XDG
        }
        return File(base, appName)
    }
}

/** Path layout + presence checks for the desktop model file. Mirrors Android's `ModelInventory`. */
class DesktopModelInventory(
    val spec: DesktopModelSpec,
    private val baseDir: File = DesktopAppDirs.dataDir(),
) {
    fun modelsDir(): File = File(baseDir, "models").apply { mkdirs() }

    /** Final, verified location — what [LlamaCppInferenceEngine.loadModel] reads. */
    fun localFile(): File = File(modelsDir(), spec.filename)

    /** Resumable in-progress download; never loaded directly. */
    fun partialFile(): File = File(modelsDir(), "${spec.filename}.partial")

    /** Cheap presence check: exists + size matches (no full re-hash on every launch). */
    fun isPresent(): Boolean {
        if (spec.sizeBytes <= 0L) return false
        val f = localFile()
        return f.exists() && f.length() == spec.sizeBytes
    }

    fun deleteAll() {
        partialFile().delete()
        localFile().delete()
    }

    companion object {
        /**
         * v1 desktop GGUF spec. URL points at a public Gemma GGUF; `sha256`/`sizeBytes` are
         * left blank so [DesktopModelSpec.isConfigured] is false until the operator fills the
         * verified values for their chosen quant (same BYO-coordinates policy as Android's
         * `secrets.properties` MODEL_SHA256/MODEL_SIZE_BYTES). The downloader refuses to run
         * with an unconfigured spec rather than write an unverifiable file.
         */
        val DEFAULT: DesktopModelSpec = DesktopModelSpec(
            filename = "gemma-4-E4B-it-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF/resolve/main/gemma-4-E4B-it-Q4_K_M.gguf",
            sha256 = "519b9793ed6ce0ff530f1b7c96e848e08e49e7af4d57bb97f76215963a54146d",
            sizeBytes = 4977169568L,
        )
    }
}

/** Outcome of one [DesktopModelDownloader.download] attempt. */
sealed interface ModelDownloadResult {
    data class Success(val file: File, val totalBytes: Long) : ModelDownloadResult
    /** [retryable] distinguishes a transient (network/5xx) miss from a permanent one. */
    data class Failure(val message: String, val retryable: Boolean) : ModelDownloadResult
}

/**
 * Streams [DesktopModelInventory.spec] into the inventory's partial file, verifies it, and
 * atomically promotes it to the final path. Suspends on [Dispatchers.IO]; honours coroutine
 * cancellation between 64 KB chunks.
 *
 * @param hfToken supplies an optional HuggingFace bearer token for gated repos (defaults to
 *   the `HF_AUTH_TOKEN` env var) — the BYOK analogue of Android's `HfAuthTokenProvider`.
 */
class DesktopModelDownloader(
    private val inventory: DesktopModelInventory,
    private val hfToken: () -> String? = { System.getenv("HF_AUTH_TOKEN") },
    private val logger: (String) -> Unit = {},
) {
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    suspend fun download(
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): ModelDownloadResult = withContext(ioDispatcher) {
        val spec = inventory.spec
        if (!spec.isConfigured) {
            return@withContext ModelDownloadResult.Failure(
                "Model spec is incomplete (url='${spec.downloadUrl}', sha256='${spec.sha256}', " +
                    "sizeBytes=${spec.sizeBytes}). Fill DesktopModelInventory.DEFAULT.",
                retryable = false,
            )
        }

        val partial = inventory.partialFile()
        val final = inventory.localFile()
        val modelsDir = inventory.modelsDir()

        // Free-space pre-check: remaining download + 200 MB headroom.
        val remaining = (spec.sizeBytes - partial.length()).coerceAtLeast(0L)
        val needed = remaining + STORAGE_HEADROOM_BYTES
        val available = modelsDir.usableSpace
        if (available < needed) {
            return@withContext ModelDownloadResult.Failure(
                "Insufficient storage: need $needed bytes free at ${modelsDir.path}, have $available.",
                retryable = false,
            )
        }

        val digest = MessageDigest.getInstance("SHA-256")
        if (partial.length() > 0L) {
            // Re-feed existing partial bytes so the streaming digest covers the whole file.
            try {
                feedDigest(partial, digest)
            } catch (e: IOException) {
                logger("partial re-read failed, restarting from 0: ${e.message}")
                partial.delete()
                digest.reset()
            }
        }

        var resumeAt = if (partial.exists()) partial.length() else 0L
        val conn = try {
            (URI(spec.downloadUrl).toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 30_000
                if (resumeAt > 0L) setRequestProperty("Range", "bytes=$resumeAt-")
                hfToken()?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
            }
        } catch (e: IOException) {
            return@withContext ModelDownloadResult.Failure("Network: ${e.message}", retryable = true)
        }

        try {
            val code = try {
                conn.responseCode
            } catch (e: IOException) {
                return@withContext ModelDownloadResult.Failure("Network: ${e.message}", retryable = true)
            }
            when (code) {
                in 200..299 -> Unit
                416 -> {
                    // Range Not Satisfiable: our partial disagrees with the server. Wipe + retry.
                    logger("HTTP 416 — discarding partial, asking caller to retry")
                    partial.delete()
                    return@withContext ModelDownloadResult.Failure("HTTP 416 — partial discarded", retryable = true)
                }
                in 400..499 -> return@withContext ModelDownloadResult.Failure("HTTP $code: ${conn.responseMessage}", retryable = false)
                in 500..599 -> return@withContext ModelDownloadResult.Failure("HTTP $code: ${conn.responseMessage}", retryable = true)
                else -> return@withContext ModelDownloadResult.Failure("Unexpected HTTP $code", retryable = true)
            }

            val isResume = code == HttpURLConnection.HTTP_PARTIAL // 206
            if (resumeAt > 0L && !isResume) {
                // Asked for a Range but got 200 — server ignored it. Restart from 0.
                logger("server returned $code for a Range request; restarting from 0")
                partial.delete()
                digest.reset()
                resumeAt = 0L
            }

            val startBytes = if (isResume && resumeAt > 0L) resumeAt else 0L
            val totalBytes = totalBytesOf(conn, spec, isResume)

            try {
                RandomAccessFile(partial, "rw").use { raf ->
                    raf.seek(startBytes)
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var written = startBytes
                        onProgress(written, totalBytes)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            written += n
                            onProgress(written, totalBytes)
                        }
                    }
                }
            } catch (e: IOException) {
                return@withContext ModelDownloadResult.Failure("Network: ${e.message}", retryable = true)
            }

            if (partial.length() != spec.sizeBytes) {
                return@withContext ModelDownloadResult.Failure(
                    "Size mismatch: expected ${spec.sizeBytes}, got ${partial.length()}",
                    retryable = false,
                )
            }
            val actual = digest.digest().toHex()
            if (!actual.equals(spec.sha256, ignoreCase = true)) {
                logger("checksum mismatch — deleting partial")
                partial.delete()
                return@withContext ModelDownloadResult.Failure(
                    "Checksum mismatch: expected=${spec.sha256} actual=$actual",
                    retryable = false,
                )
            }

            if (!partial.renameTo(final)) {
                logger("renameTo failed; falling back to copy+delete")
                try {
                    partial.copyTo(final, overwrite = true)
                    partial.delete()
                } catch (e: IOException) {
                    return@withContext ModelDownloadResult.Failure("Promote failed: ${e.message}", retryable = true)
                }
            }
            logger("download complete: ${final.path} ($totalBytes bytes)")
            ModelDownloadResult.Success(final, totalBytes)
        } finally {
            conn.disconnect()
        }
    }

    private fun totalBytesOf(conn: HttpURLConnection, spec: DesktopModelSpec, isResume: Boolean): Long {
        if (isResume) {
            // Content-Range: bytes 1024-2047/2048 → the part after '/'.
            val cr = conn.getHeaderField("Content-Range") ?: return spec.sizeBytes
            return cr.substringAfter('/').toLongOrNull() ?: spec.sizeBytes
        }
        val cl = conn.contentLengthLong
        return if (cl > 0L) cl else spec.sizeBytes
    }

    private fun feedDigest(file: File, digest: MessageDigest) {
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
    }

    companion object {
        const val STORAGE_HEADROOM_BYTES: Long = 200L * 1024 * 1024 // 200 MB
    }
}

private fun ByteArray.toHex(): String = buildString(size * 2) { this@toHex.forEach { append("%02x".format(it)) } }
