package com.contextsolutions.localagent.voice

import com.contextsolutions.localagent.platform.DesktopDiag
import com.contextsolutions.localagent.inference.DesktopAppDirs
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
 * Acquires the offline Vosk acoustic model for desktop dictation ([VoskDictation]).
 * The model is ~40 MB — too large to bundle — so, like the GGUF and `llama-server`,
 * it's downloaded on first use and cached under `<app-data>/models/vosk`, reusing
 * [DesktopModelDownloader]'s resumable / SHA-verified streaming; only the unpack +
 * promote step is new. Fully offline once present.
 *
 * Resolution order (first hit wins):
 *   1. `LOCALAGENT_VOSK_MODEL` env → an already-extracted model dir (dev / BYO).
 *   2. `<app-data>/models/vosk` if it's already a valid model dir.
 *   3. download + extract the pinned small-English model, then (2).
 *
 * A "valid model dir" is one that directly contains Vosk's `conf/` subdir, so a
 * user can also just drop an extracted model at `<app-data>/models/vosk` by hand.
 */
class VoskModelStore(
    private val baseDir: File = DesktopAppDirs.dataDir(),
    private val downloaderFactory: (DesktopModelInventory) -> DesktopModelDownloader = { inv ->
        DesktopModelDownloader(inv, logger = { DesktopDiag.log("[VoskModelDownload] $it") })
    },
    private val logger: (String) -> Unit = { DesktopDiag.log("[VoskModel] $it") },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val modelsDir: File get() = File(baseDir, "models").apply { mkdirs() }

    /** Final, extracted model dir — what [org.vosk.Model] loads. */
    private val modelDir: File get() = File(modelsDir, MODEL_DIR_NAME)

    // Serializes acquisition so a stray double-toggle of the mic can't race two
    // downloads onto the same files (mirrors LlamaServerBinaryStore.acquireMutex).
    private val acquireMutex = Mutex()

    private fun isModelDir(dir: File): Boolean = dir.isDirectory && File(dir, "conf").isDirectory

    private fun envOverride(): File? =
        System.getenv(ENV_OVERRIDE)?.takeIf { it.isNotBlank() }?.let { File(it).takeIf(::isModelDir) }

    /** The model dir if already available (env override or extracted cache); no download. */
    fun resolveExistingOrNull(): String? =
        envOverride()?.absolutePath ?: modelDir.takeIf(::isModelDir)?.absolutePath

    /**
     * Returns the path to a ready Vosk model dir, downloading + extracting it on first
     * call. Idempotent. Returns null (never throws) when the model can't be obtained —
     * offline, no disk, etc. — so dictation degrades to a no-op rather than crashing the
     * app, matching the rest of the desktop "missing artifact ⇒ silent disable" policy.
     * Runs on [ioDispatcher]; honours cancellation (mic toggled off mid-download).
     */
    suspend fun ensure(
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): String? = withContext(ioDispatcher) {
        resolveExistingOrNull()?.let { return@withContext it }

        acquireMutex.withLock {
            // Re-check inside the lock: a concurrent caller may have just finished.
            resolveExistingOrNull()?.let { return@withLock it }

            logger("downloading speech model ${SMALL_EN.filename} (~${SMALL_EN.sizeBytes / 1_000_000} MB)…")
            val inventory = DesktopModelInventory(SMALL_EN, baseDir)
            when (val result = downloaderFactory(inventory).download(onProgress)) {
                is ModelDownloadResult.Success -> extractAndPromote(result.file)
                is ModelDownloadResult.Failure -> {
                    logger("speech-model download failed (retryable=${result.retryable}): ${result.message}")
                    return@withLock null
                }
            }
            modelDir.takeIf(::isModelDir)?.absolutePath?.also { logger("speech model ready: $it") }
        }
    }

    /**
     * Extract the `.tar.gz` into `models/`. The archive contains a single `vosk/` root that
     * already matches [MODEL_DIR_NAME], so extracting into `models/` yields `models/vosk`
     * directly — no separate promote step. Uses the system `tar` (GNU on Linux, bsdtar on
     * macOS/Windows 10+), which autodetects gzip; `java.util.zip` can't read a `.tar.gz`.
     */
    private fun extractAndPromote(archive: File) {
        // Clear any stale model dir so the extract can't merge with old files.
        modelDir.deleteRecursively()
        val proc = ProcessBuilder("tar", "-xf", archive.absolutePath, "-C", modelsDir.absolutePath)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        check(code == 0) { "tar extract failed (exit=$code): ${out.take(500)}" }
        archive.delete()
        check(isModelDir(modelDir)) { "extracted archive missing model files at ${modelDir.path}" }
    }

    private companion object {
        const val ENV_OVERRIDE = "LOCALAGENT_VOSK_MODEL"
        const val MODEL_DIR_NAME = "vosk"

        // Small English Vosk model (~40 MB, vosk-model-small-en-us) mirrored to the public
        // downloads CDN (PR #22/#28), no auth. The archive packs a single `vosk/` root
        // (matching MODEL_DIR_NAME); sha256/size are the verified values of the hosted
        // `vosk.tar.gz` (re-pinned to the re-gzipped archive served from downloads.contextsolutions.com).
        val SMALL_EN = DesktopModelSpec(
            filename = "vosk.tar.gz",
            downloadUrl = "${com.contextsolutions.localagent.inference.DesktopAuxModels.DEFAULT_BASE_URL}/vosk.tar.gz",
            sha256 = "a1e30e411a7202aa1755d3d477e4fcea56a43a0d331b357cb80683d0e4ef68bc",
            sizeBytes = 41_207_126,
        )
    }
}
