package com.contextsolutions.localagent.app.service

import android.content.Context
import androidx.annotation.WorkerThread
import com.contextsolutions.localagent.app.BuildConfig
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Source of truth for the model artifacts this build downloads / loads: the
 * Gemma 4 E2B LLM ([SPEC]) plus the two aux models — the pre-flight classifier
 * and the MiniLM embedder ([AndroidAuxModels], PR #3, previously bundled in the
 * APK and now CDN-downloaded on first run).
 *
 * Path layout (per discussion in WS-1 Phase B):
 *   filesDir/models/<filename>           — the verified, ready-to-load file
 *   filesDir/models/<filename>.partial   — in-progress download
 *
 * Note that this is internal storage (`filesDir`), not external (`getExternalFilesDir`)
 * which the M0 spike still uses for adb-push convenience. Production code reads
 * from internal; the spike's manual sideload path is unaffected.
 *
 * All specs are hardcoded per build; [requiredSpecs] is the set the first-run
 * download gate must satisfy before chat unlocks.
 */
class ModelInventory(
    private val context: Context,
) {

    /** The Gemma LLM artifact this build will download / load. */
    fun spec(): ModelSpec = SPEC

    /**
     * Every model the first-run gate requires on disk before chat: the Gemma LLM
     * plus the two aux models. Order is download order (LLM first, then aux).
     */
    fun requiredSpecs(): List<ModelSpec> = listOf(SPEC) + AndroidAuxModels.SPECS

    /** Returns (and creates if missing) the models directory under filesDir. */
    fun modelsDir(): File = File(context.filesDir, "models").apply { mkdirs() }

    /** Final, verified location for [spec] (the Gemma LLM). Loaded by the inference engine. */
    fun localFile(): File = localFile(spec())

    /** Resumable in-progress download for [spec]. Never loaded directly. */
    fun partialFile(): File = partialFile(spec())

    /** Final, verified location for an arbitrary [s]. */
    fun localFile(s: ModelSpec): File = File(modelsDir(), s.filename)

    /** Resumable in-progress download for an arbitrary [s]. Never loaded directly. */
    fun partialFile(s: ModelSpec): File = File(modelsDir(), "${s.filename}.partial")

    /**
     * Cheap presence check used on every chat-screen entry. Verifies the file
     * exists and matches the expected size — sufficient for "did the download
     * finish" without re-hashing on every launch.
     *
     * If [ModelSpec.sizeBytes] is 0 (spec not configured), this returns false:
     * we never load an unspecified artifact.
     */
    fun isPresent(): Boolean = isPresent(spec())

    /** Size-match presence check for an arbitrary [s]. */
    fun isPresent(s: ModelSpec): Boolean {
        if (s.sizeBytes <= 0L) return false
        val f = localFile(s)
        return f.exists() && f.length() == s.sizeBytes
    }

    /** True once every [requiredSpecs] file is present (the first-run gate). */
    fun allRequiredPresent(): Boolean = requiredSpecs().all { isPresent(it) }

    /** True once both aux models (classifier + embedder) are on disk. */
    fun auxModelsPresent(): Boolean = AndroidAuxModels.SPECS.all { isPresent(it) }

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
            requiresHfAuth = true,
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
 * [ModelInventory.SPEC] / [AndroidAuxModels]; consumed by [ModelDownloader]
 * (URL, checksum, expected size) and the inference path (filename →
 * [ModelInventory.localFile]).
 *
 * [requiresHfAuth] gates the `Authorization: Bearer <HF token>` header: the
 * Gemma artifact is served from HuggingFace (true), the aux models from the
 * public CDN (false), so an HF token is never leaked to the CDN.
 */
data class ModelSpec(
    val filename: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val requiresHfAuth: Boolean = false,
) {
    /** True if all fields look usable (URL + checksum + size all configured). */
    val isConfigured: Boolean = downloadUrl.isNotBlank() && sha256.isNotBlank() && sizeBytes > 0L
}
