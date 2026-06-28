package com.contextsolutions.localagent.voice

import com.contextsolutions.localagent.platform.DesktopDiag
import com.contextsolutions.localagent.inference.DesktopAppDirs
import com.contextsolutions.localagent.inference.DesktopAuxModels
import com.contextsolutions.localagent.inference.DesktopModelDownloader
import com.contextsolutions.localagent.inference.DesktopModelInventory
import com.contextsolutions.localagent.inference.DesktopModelSpec
import com.contextsolutions.localagent.inference.ModelDownloadResult
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A downloadable Piper neural voice (PR #66): the ONNX weights + their `.onnx.json`
 * config, fetched from the downloads.contextsolutions.com CDN (originally sourced from
 * the `rhasspy/piper-voices` repo). [sampleRate] feeds the JVM audio line
 * ([PiperSpeechSynthesizer]); Piper auto-loads the config because it sits beside the
 * model named `<onnx>.json`.
 */
data class PiperVoiceSpec(
    val id: String,
    val label: String,
    val onnxUrl: String,
    val onnxSha256: String,
    val onnxSizeBytes: Long,
    val jsonUrl: String,
    val jsonSha256: String,
    val jsonSizeBytes: Long,
    val sampleRate: Int,
) {
    val onnxFilename: String get() = "$id.onnx"
    val jsonFilename: String get() = "$id.onnx.json"
    val totalBytes: Long get() = onnxSizeBytes + jsonSizeBytes
}

/** The bundled voice catalog. One curated en-US voice for now; structured to add more. */
object PiperVoices {
    // downloads.contextsolutions.com CDN base (`/models` path, files served flat), shared
    // with the aux models + Vosk. Upload these two files there (same bytes, so the sha256
    // pins below still match) — see DesktopAuxModels for the host.
    private val CDN = DesktopAuxModels.DEFAULT_BASE_URL

    val LESSAC_MEDIUM = PiperVoiceSpec(
        id = "en_US-lessac-medium",
        label = "Lessac — neural (en-US)",
        onnxUrl = "$CDN/en_US-lessac-medium.onnx",
        onnxSha256 = "5efe09e69902187827af646e1a6e9d269dee769f9877d17b16b1b46eeaaf019f",
        onnxSizeBytes = 63_201_294,
        jsonUrl = "$CDN/en_US-lessac-medium.onnx.json",
        jsonSha256 = "efe19c417bed055f2d69908248c6ba650fa135bc868b0e6abb3da181dab690a0",
        jsonSizeBytes = 4_885,
        sampleRate = 22_050,
    )

    val ALL: List<PiperVoiceSpec> = listOf(LESSAC_MEDIUM)
    val DEFAULT: PiperVoiceSpec = LESSAC_MEDIUM

    fun byId(id: String?): PiperVoiceSpec = ALL.firstOrNull { it.id == id } ?: DEFAULT
}

/** Resolved voice on disk: the model path piper loads + its sample rate. */
data class PiperVoiceFiles(val onnx: File, val sampleRate: Int)

/**
 * Downloads + caches a [PiperVoiceSpec]'s model files on first use (PR #66), mirroring
 * [VoskModelStore]/[PiperBinaryStore]. The `.onnx` and `.onnx.json` land side by side so
 * Piper finds the config automatically. [ensure] returns null on download failure.
 */
class PiperVoiceStore(
    private val baseDir: File = DesktopAppDirs.dataDir(),
    private val downloaderFactory: (DesktopModelInventory) -> DesktopModelDownloader = { inv ->
        DesktopModelDownloader(inv, logger = { DesktopDiag.log("[PiperVoiceDownload] $it") })
    },
    private val logger: (String) -> Unit = { DesktopDiag.log("[PiperVoice] $it") },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val voicesDir: File get() = File(baseDir, "piper/voices")
    private val acquireMutex = Mutex()

    private fun inventory(filename: String, url: String, sha: String, size: Long) =
        DesktopModelInventory(DesktopModelSpec(filename, url, sha, size), baseDir = voicesDir)

    fun resolveExistingOrNull(spec: PiperVoiceSpec): PiperVoiceFiles? {
        val onnx = inventory(spec.onnxFilename, spec.onnxUrl, spec.onnxSha256, spec.onnxSizeBytes)
        val json = inventory(spec.jsonFilename, spec.jsonUrl, spec.jsonSha256, spec.jsonSizeBytes)
        return if (onnx.isPresent() && json.isPresent()) {
            PiperVoiceFiles(onnx.localFile(), spec.sampleRate)
        } else {
            null
        }
    }

    suspend fun ensure(
        spec: PiperVoiceSpec,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): PiperVoiceFiles? = withContext(ioDispatcher) {
        resolveExistingOrNull(spec)?.let { return@withContext it }

        acquireMutex.withLock {
            resolveExistingOrNull(spec)?.let { return@withLock it }

            logger("downloading Piper voice ${spec.id} (~${spec.totalBytes / 1_000_000} MB)…")
            val total = spec.totalBytes
            // Sequential downloads share one progress bar: the config is tiny, so offset
            // the config's bytes by the model size for a monotonic overall fraction.
            val onnxOk = download(
                inventory(spec.onnxFilename, spec.onnxUrl, spec.onnxSha256, spec.onnxSizeBytes),
            ) { done, _ -> onProgress(done, total) }
            if (!onnxOk) return@withLock null
            val jsonOk = download(
                inventory(spec.jsonFilename, spec.jsonUrl, spec.jsonSha256, spec.jsonSizeBytes),
            ) { done, _ -> onProgress(spec.onnxSizeBytes + done, total) }
            if (!jsonOk) return@withLock null

            resolveExistingOrNull(spec)?.also { logger("Piper voice ready: ${it.onnx.path}") }
        }
    }

    private suspend fun download(
        inv: DesktopModelInventory,
        onProgress: (Long, Long) -> Unit,
    ): Boolean = when (val r = downloaderFactory(inv).download(onProgress)) {
        is ModelDownloadResult.Success -> true
        is ModelDownloadResult.Failure -> {
            logger("voice file ${inv.spec.filename} failed (retryable=${r.retryable}): ${r.message}")
            false
        }
    }
}
