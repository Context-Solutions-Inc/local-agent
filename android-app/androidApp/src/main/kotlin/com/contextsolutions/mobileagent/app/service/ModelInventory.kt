package com.contextsolutions.mobileagent.app.service

import android.content.Context
import androidx.annotation.WorkerThread
import com.contextsolutions.mobileagent.app.BuildConfig
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single source of truth for the v1 Gemma 4 E2B artifact.
 *
 * Path layout (per discussion in WS-1 Phase B):
 *   filesDir/models/<filename>.litertlm           — the verified, ready-to-load file
 *   filesDir/models/<filename>.litertlm.partial   — in-progress download
 *
 * Note that this is internal storage (`filesDir`), not external (`getExternalFilesDir`)
 * which the M0 spike still uses for adb-push convenience. Production code reads
 * from internal; the spike's manual sideload path is unaffected.
 *
 * The spec is hardcoded for v1 (single model). Multi-model lands when we ever
 * support runtime swapping; for now, [SPEC] is constant per build.
 */
class ModelInventory(
    private val context: Context,
) {

    /** The one model artifact this build will download / load. */
    fun spec(): ModelSpec = SPEC

    /** Returns (and creates if missing) the models directory under filesDir. */
    fun modelsDir(): File = File(context.filesDir, "models").apply { mkdirs() }

    /** Final, verified location. Loaded by the inference engine. */
    fun localFile(): File = File(modelsDir(), spec().filename)

    /** Resumable in-progress download. Never loaded directly. */
    fun partialFile(): File = File(modelsDir(), "${spec().filename}.partial")

    /**
     * Cheap presence check used on every chat-screen entry. Verifies the file
     * exists and matches the expected size — sufficient for "did the download
     * finish" without re-hashing 2.58 GB on every launch.
     *
     * If [ModelSpec.sizeBytes] is 0 (spec not configured), this returns false:
     * we never load an unspecified artifact.
     */
    fun isPresent(): Boolean {
        val s = spec()
        if (s.sizeBytes <= 0L) return false
        val f = localFile()
        return f.exists() && f.length() == s.sizeBytes
    }

    /**
     * Full SHA-256 verification. Slow on a 2.58 GB file (~5–8 s on Pixel 7's UFS
     * 3.1) — only call after a fresh download or when the user explicitly
     * requests revalidation. Returns false if the file is missing or the spec
     * hash is empty.
     */
    @WorkerThread
    suspend fun verifyChecksum(): Boolean = withContext(Dispatchers.IO) {
        val s = spec()
        if (s.sha256.isBlank()) return@withContext false
        val f = localFile()
        if (!f.exists()) return@withContext false
        sha256Of(f).equals(s.sha256, ignoreCase = true)
    }

    /** Removes both partial and final files. Used after a failed checksum. */
    fun deleteAll() {
        partialFile().delete()
        localFile().delete()
    }

    companion object {
        @JvmStatic
        val SPEC: ModelSpec = ModelSpec(
            filename = "gemma-4-E2B-it.litertlm",
            downloadUrl = BuildConfig.MODEL_DOWNLOAD_URL,
            sha256 = BuildConfig.MODEL_SHA256,
            sizeBytes = BuildConfig.MODEL_SIZE_BYTES,
        )

        /**
         * Streamed SHA-256. Exposed at companion level so [ModelDownloader] can
         * use the same routine on the partial file without going through the
         * inventory instance.
         */
        @WorkerThread
        fun sha256Of(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().toHex()
        }

        private fun ByteArray.toHex(): String =
            buildString(this@toHex.size * 2) {
                this@toHex.forEach { append("%02x".format(it)) }
            }
    }
}

/**
 * Immutable description of one model artifact. Hardcoded per build via
 * [ModelInventory.SPEC]; consumed by [ModelDownloader] (URL, checksum, expected
 * size) and the inference path (filename → [ModelInventory.localFile]).
 */
data class ModelSpec(
    val filename: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
) {
    /** True if all fields look usable (URL + checksum + size all configured). */
    val isConfigured: Boolean = downloadUrl.isNotBlank() && sha256.isNotBlank() && sizeBytes > 0L
}
