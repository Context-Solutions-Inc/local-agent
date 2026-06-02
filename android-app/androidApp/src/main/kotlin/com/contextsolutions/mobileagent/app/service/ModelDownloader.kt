package com.contextsolutions.mobileagent.app.service

import android.os.StatFs
import android.util.Log
import com.contextsolutions.mobileagent.inference.HfAuthTokenProvider
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads the Gemma 4 artifact described by [ModelInventory.spec].
 *
 * Resumable: a `<filename>.partial` is appended-to across runs, with the
 * SHA-256 digest re-fed from existing bytes on resume so the streaming hash
 * stays correct. On completion the partial is atomically renamed to the final
 * filename — readers (the inference engine via [ModelInventory.localFile]) only
 * ever see a fully-verified artifact.
 *
 * Failure modes (mapped to [DownloadError]) — the caller decides retry vs.
 * surface-to-user:
 *   - [DownloadError.Network]: IOException during the HTTP read. Worker retries.
 *   - [DownloadError.HttpClient]: 4xx (other than the resume edge cases). Permanent.
 *     416 (Range Not Satisfiable) is treated as "partial is bad, restart" rather
 *     than a hard failure.
 *   - [DownloadError.HttpServer]: 5xx. Worker retries.
 *   - [DownloadError.Storage]: not enough free space at start. Permanent until
 *     the user frees space.
 *   - [DownloadError.Checksum]: SHA-256 mismatch after a complete download. The
 *     partial is deleted; permanent (something's fundamentally wrong upstream).
 *   - [DownloadError.Misconfigured]: BuildConfig.MODEL_SHA256/SIZE not filled in.
 *     Engineer-side problem; permanent.
 */
class ModelDownloader(
    private val inventory: ModelInventory,
    private val httpClient: OkHttpClient,
    private val hfAuthTokenProvider: HfAuthTokenProvider,
) {

    /** Mutable for tests; defaults to IO. */
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    /**
     * Runs one download attempt. Returns either [DownloadOutcome.Success] (the
     * artifact is now at [ModelInventory.localFile]) or [DownloadOutcome.Failure]
     * with a structured error. Suspends; respects coroutine cancellation between
     * chunks.
     *
     * @param onProgress invoked roughly per 64 KB chunk with `(downloadedBytes,
     *   totalBytes)`. Called from the IO dispatcher. Cheap callbacks only —
     *   anything slow throttles the download.
     */
    suspend fun download(
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): DownloadOutcome = withContext(ioDispatcher) {
        val spec = inventory.spec()
        if (!spec.isConfigured) {
            return@withContext DownloadOutcome.Failure(
                DownloadError.Misconfigured(
                    "Model spec is incomplete: url='${spec.downloadUrl}', " +
                        "sha256='${spec.sha256}', sizeBytes=${spec.sizeBytes}. " +
                        "Fill in secrets.properties (MODEL_SHA256, MODEL_SIZE_BYTES)."
                ),
            )
        }

        val partial = inventory.partialFile()
        val final = inventory.localFile()
        inventory.modelsDir() // ensure dir exists

        // Storage pre-check: free space must cover the *remaining* download
        // plus a 200 MB headroom (random small-file noise + filesystem block
        // overhead). PRD §3.5: "If the device has insufficient storage, the app
        // must surface this clearly and refuse to download."
        val remaining = (spec.sizeBytes - partial.length()).coerceAtLeast(0L)
        val available = StatFs(inventory.modelsDir().path).availableBytes
        val needed = remaining + STORAGE_HEADROOM_BYTES
        if (available < needed) {
            return@withContext DownloadOutcome.Failure(
                DownloadError.Storage(neededBytes = needed, availableBytes = available),
            )
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val resumeFrom = partial.length()
        if (resumeFrom > 0L) {
            // Re-feed existing partial bytes so the digest covers the full file
            // when we eventually compare. MessageDigest isn't serializable so
            // intermediate state can't be cached on disk — the re-read is the
            // simplest correct approach.
            try {
                feedDigestFromExisting(partial, digest)
            } catch (e: IOException) {
                Log.w(TAG, "Failed to re-read partial; restarting from 0.", e)
                partial.delete()
                digest.reset()
            }
        }

        val attemptResumeAt = if (partial.exists()) partial.length() else 0L
        val hfToken = hfAuthTokenProvider.currentToken()
        val request = Request.Builder()
            .url(spec.downloadUrl)
            .apply {
                if (attemptResumeAt > 0L) header("Range", "bytes=$attemptResumeAt-")
                if (!hfToken.isNullOrBlank()) {
                    header("Authorization", "Bearer $hfToken")
                }
            }
            .build()

        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            return@withContext DownloadOutcome.Failure(DownloadError.Network(e))
        }

        return@withContext response.use { resp ->
            when (resp.code) {
                in 200..299 -> Unit
                416 -> {
                    // Range Not Satisfiable: server has a different file size than
                    // our partial reflects. Wipe partial and let the worker retry.
                    Log.w(TAG, "HTTP 416 — wiping partial and asking caller to retry.")
                    partial.delete()
                    return@use DownloadOutcome.Failure(DownloadError.Network(IOException("HTTP 416 — partial discarded")))
                }
                in 400..499 -> return@use DownloadOutcome.Failure(
                    DownloadError.HttpClient(resp.code, resp.message),
                )
                in 500..599 -> return@use DownloadOutcome.Failure(
                    DownloadError.HttpServer(resp.code, resp.message),
                )
                else -> return@use DownloadOutcome.Failure(
                    DownloadError.Network(IOException("Unexpected HTTP code ${resp.code}")),
                )
            }

            val isResume = resp.code == 206
            if (attemptResumeAt > 0L && !isResume) {
                // We asked for a Range but got 200 (server doesn't support it).
                // Restart from byte 0: clear digest, truncate partial.
                Log.w(TAG, "Server returned 200 for Range request; restarting from 0.")
                partial.delete()
                digest.reset()
            }

            val totalBytes = computeTotalBytes(resp, spec, attemptResumeAt, isResume)
            val startBytes = if (isResume && attemptResumeAt > 0L) attemptResumeAt else 0L

            try {
                writeBody(
                    body = resp.body ?: return@use DownloadOutcome.Failure(
                        DownloadError.Network(IOException("Empty response body")),
                    ),
                    partial = partial,
                    digest = digest,
                    startBytes = startBytes,
                    totalBytes = totalBytes,
                    onProgress = onProgress,
                )
            } catch (e: IOException) {
                return@use DownloadOutcome.Failure(DownloadError.Network(e))
            }

            // Verify final size matches the spec.
            if (partial.length() != spec.sizeBytes) {
                return@use DownloadOutcome.Failure(
                    DownloadError.Checksum(
                        expected = spec.sha256,
                        actual = "(size mismatch: expected ${spec.sizeBytes}, got ${partial.length()})",
                    ),
                )
            }

            // Verify SHA-256.
            val actual = digest.digest().toHex()
            if (!actual.equals(spec.sha256, ignoreCase = true)) {
                Log.w(TAG, "Checksum mismatch — deleting partial.")
                partial.delete()
                return@use DownloadOutcome.Failure(
                    DownloadError.Checksum(expected = spec.sha256, actual = actual),
                )
            }

            // Atomic rename on the same filesystem. If this fails (very rare —
            // some emulators or fuse mounts), fall back to copy+delete.
            if (!partial.renameTo(final)) {
                Log.w(TAG, "renameTo failed; falling back to copy+delete.")
                try {
                    partial.copyTo(final, overwrite = true)
                    partial.delete()
                } catch (e: IOException) {
                    return@use DownloadOutcome.Failure(DownloadError.Network(e))
                }
            }

            DownloadOutcome.Success(final, totalBytes)
        }
    }

    private suspend fun writeBody(
        body: okhttp3.ResponseBody,
        partial: File,
        digest: MessageDigest,
        startBytes: Long,
        totalBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        RandomAccessFile(partial, "rw").use { raf ->
            raf.seek(startBytes)
            body.byteStream().use { input ->
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
    }

    private fun computeTotalBytes(
        resp: okhttp3.Response,
        spec: ModelSpec,
        attemptResumeAt: Long,
        isResume: Boolean,
    ): Long {
        if (isResume) {
            // Content-Range: bytes 1024-2047/2048 → grab the part after the slash.
            val cr = resp.header("Content-Range") ?: return spec.sizeBytes
            return cr.substringAfter('/').toLongOrNull() ?: spec.sizeBytes
        }
        val cl = resp.body?.contentLength() ?: -1L
        return if (cl > 0L) cl else spec.sizeBytes
    }

    private fun feedDigestFromExisting(file: File, digest: MessageDigest) {
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
        private const val TAG = "ModelDownloader"
        const val STORAGE_HEADROOM_BYTES: Long = 200L * 1024 * 1024  // 200 MB
    }
}

sealed interface DownloadOutcome {
    data class Success(val file: File, val totalBytes: Long) : DownloadOutcome
    data class Failure(val error: DownloadError) : DownloadOutcome
}

sealed interface DownloadError {
    val isRetryable: Boolean
    val message: String

    data class Network(val cause: Throwable) : DownloadError {
        override val isRetryable = true
        override val message: String = "Network: ${cause.message ?: cause::class.simpleName}"
    }
    data class HttpClient(val code: Int, val httpMessage: String) : DownloadError {
        override val isRetryable = false
        override val message: String = "HTTP $code: $httpMessage"
    }
    data class HttpServer(val code: Int, val httpMessage: String) : DownloadError {
        override val isRetryable = true
        override val message: String = "HTTP $code: $httpMessage"
    }
    data class Storage(val neededBytes: Long, val availableBytes: Long) : DownloadError {
        override val isRetryable = false
        override val message: String =
            "Need $neededBytes bytes free, have $availableBytes."
    }
    data class Checksum(val expected: String, val actual: String) : DownloadError {
        override val isRetryable = false
        override val message: String = "Checksum mismatch: expected=$expected actual=$actual"
    }
    data class Misconfigured(val detail: String) : DownloadError {
        override val isRetryable = false
        override val message: String = detail
    }
}

private fun ByteArray.toHex(): String =
    buildString(size * 2) {
        this@toHex.forEach { append("%02x".format(it)) }
    }
