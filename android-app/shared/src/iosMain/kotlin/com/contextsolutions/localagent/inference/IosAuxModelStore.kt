package com.contextsolutions.localagent.inference

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.seekToEndOfFile
import platform.Foundation.writeData

/** One auxiliary (`.onnx`) model spec — the FP32 classifier / embedder pins. */
data class IosAuxModelSpec(
    val filename: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
)

/**
 * iOS auxiliary-model coordinates (Phase 2). The FP32 `.onnx` flavor desktop uses —
 * the INT8 `.tflite` files are `ai-edge-litert`-specific / Android-only (invariant
 * #18), while ONNX Runtime's iOS build consumes these directly. Pins match
 * `DesktopAuxModels`; hosted on the public models CDN (invariant #61), no auth.
 */
object IosAuxModels {
    val CLASSIFIER = IosAuxModelSpec(
        filename = "preflight_memory_shared_v1.0.0.onnx",
        url = "https://downloads.contextsolutions.com/models/preflight_memory_shared_v1.0.0.onnx",
        sha256 = "8e4e6bcc97715f4323ecf99622bd70a379c735ebcd000bf683d80b7205acfaea",
        sizeBytes = 266_092_463L,
    )
    val EMBEDDER = IosAuxModelSpec(
        filename = "all-MiniLM-L6-v2.onnx",
        url = "https://downloads.contextsolutions.com/models/all-MiniLM-L6-v2.onnx",
        sha256 = "b1ccb58ee5c9f4af251cf9f3b4147a883a75441fa24763c002af587f65da28bd",
        sizeBytes = 91_078_619L,
    )
}

/**
 * iOS resumable downloader for the two aux `.onnx` models (Phase 2), a spec-parameterized
 * mirror of [IosModelStore] — `Range` resume, byte-size verification, excluded from iCloud
 * backup, into `<AppSupport>/models`. Deliberately duplicated from [IosModelStore] rather
 * than sharing (both downloaders are platform-coupled and standalone, per IosModelStore's
 * design note).
 *
 * `*PathIfPresent()` returns the on-disk path only when the file is at its full expected
 * size — the download-gate the [com.contextsolutions.localagent.classifier.OnnxIosClassifierEngine]
 * / [com.contextsolutions.localagent.memory.OnnxIosEmbedderEngine] `warmUp()` consult.
 *
 * (SHA-256 verification is a follow-up — size-only, matching [IosModelStore]; the ONNX
 * session failing to load a corrupt graph is the backstop.)
 */
@OptIn(ExperimentalForeignApi::class)
class IosAuxModelStore {

    private val fileManager = NSFileManager.defaultManager

    private fun modelsDir(): String {
        val base = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true,
        ).firstOrNull() as? String ?: error("no Application Support dir")
        val dir = "$base/models"
        if (!fileManager.fileExistsAtPath(dir)) {
            fileManager.createDirectoryAtPath(dir, true, null, null)
        }
        return dir
    }

    private fun pathOf(spec: IosAuxModelSpec): String = "${modelsDir()}/${spec.filename}"

    private fun isPresent(spec: IosAuxModelSpec): Boolean = fileSize(pathOf(spec)) == spec.sizeBytes

    private fun pathIfPresent(spec: IosAuxModelSpec): String? =
        pathOf(spec).takeIf { fileSize(it) == spec.sizeBytes }

    fun classifierPathIfPresent(): String? = pathIfPresent(IosAuxModels.CLASSIFIER)

    fun embedderPathIfPresent(): String? = pathIfPresent(IosAuxModels.EMBEDDER)

    suspend fun ensureClassifier(client: HttpClient, onProgress: (Float) -> Unit): String =
        ensure(IosAuxModels.CLASSIFIER, client, onProgress)

    suspend fun ensureEmbedder(client: HttpClient, onProgress: (Float) -> Unit): String =
        ensure(IosAuxModels.EMBEDDER, client, onProgress)

    /**
     * Ensure [spec] is present, resuming a partial download. Returns the local path on
     * success; throws on a network/IO failure (the warmer catches + degrades). Mirrors
     * [IosModelStore.ensure].
     */
    private suspend fun ensure(
        spec: IosAuxModelSpec,
        client: HttpClient,
        onProgress: (Float) -> Unit,
    ): String {
        val path = pathOf(spec)
        if (isPresent(spec)) {
            onProgress(1f)
            return path
        }
        var have = fileSize(path).coerceAtLeast(0L)
        if (have > spec.sizeBytes) {
            // Corrupt/oversized partial — start over.
            fileManager.removeItemAtPath(path, null)
            have = 0L
        }
        if (have == 0L) fileManager.createFileAtPath(path, null, null)

        val handle = NSFileHandle.fileHandleForWritingAtPath(path)
            ?: error("cannot open $path for writing")
        handle.seekToEndOfFile()

        client.prepareGet(spec.url) {
            if (have > 0L) headers { append(HttpHeaders.Range, "bytes=$have-") }
        }.execute { response ->
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(CHUNK)
            var written = have
            while (true) {
                val n = channel.readAvailable(buffer, 0, buffer.size)
                if (n == -1) break
                if (n > 0) {
                    handle.writeData(buffer.copyOf(n).toNSData())
                    written += n
                    onProgress((written.toFloat() / spec.sizeBytes).coerceIn(0f, 1f))
                }
            }
        }
        handle.closeAndReturnError(null)
        markExcludedFromBackup(path)
        if (!isPresent(spec)) {
            error("download incomplete (${fileSize(path)}/${spec.sizeBytes})")
        }
        return path
    }

    private fun fileSize(path: String): Long {
        val attrs = fileManager.attributesOfItemAtPath(path, null) ?: return -1L
        val size = attrs["NSFileSize"] as? NSNumber ?: return -1L
        return size.longLongValue
    }

    private fun markExcludedFromBackup(path: String) {
        val url = NSURL.fileURLWithPath(path)
        url.setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
    }

    private companion object {
        const val CHUNK = 1 shl 16 // 64 KiB
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
        }
    }
