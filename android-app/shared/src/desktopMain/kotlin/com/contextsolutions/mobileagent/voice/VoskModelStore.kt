package com.contextsolutions.mobileagent.voice

import com.contextsolutions.mobileagent.inference.DesktopAppDirs
import com.contextsolutions.mobileagent.inference.DesktopModelDownloader
import com.contextsolutions.mobileagent.inference.DesktopModelInventory
import com.contextsolutions.mobileagent.inference.DesktopModelSpec
import com.contextsolutions.mobileagent.inference.ModelDownloadResult
import java.io.File
import java.util.zip.ZipInputStream
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
 *   1. `MOBILEAGENT_VOSK_MODEL` env → an already-extracted model dir (dev / BYO).
 *   2. `<app-data>/models/vosk` if it's already a valid model dir.
 *   3. download + extract the pinned small-English model, then (2).
 *
 * A "valid model dir" is one that directly contains Vosk's `conf/` subdir, so a
 * user can also just drop an extracted model at `<app-data>/models/vosk` by hand.
 */
class VoskModelStore(
    private val baseDir: File = DesktopAppDirs.dataDir(),
    private val downloaderFactory: (DesktopModelInventory) -> DesktopModelDownloader = { inv ->
        DesktopModelDownloader(inv, logger = { System.err.println("[VoskModelDownload] $it") })
    },
    private val logger: (String) -> Unit = { System.err.println("[VoskModel] $it") },
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
     * Unzip the archive into `models/` (yielding `models/$ARCHIVE_ROOT/`) and promote it to
     * `models/vosk`. Uses [ZipInputStream] rather than the system `tar`: the model ships as a
     * `.zip`, which GNU tar on Linux can't read (only bsdtar/Windows can), and a model dir is
     * plain files with no symlinks to preserve — so the JDK unzip is correct on every platform.
     */
    private fun extractAndPromote(archive: File) {
        val extractedRoot = File(modelsDir, ARCHIVE_ROOT)
        extractedRoot.deleteRecursively()
        val canonicalModels = modelsDir.canonicalFile
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(modelsDir, entry.name)
                // Zip-slip guard: every entry must resolve inside modelsDir.
                check(target.canonicalFile.toPath().startsWith(canonicalModels.toPath())) {
                    "unsafe zip entry: ${entry.name}"
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
        archive.delete()
        check(isModelDir(extractedRoot)) { "extracted archive missing model files at ${extractedRoot.path}" }
        modelDir.deleteRecursively()
        check(extractedRoot.renameTo(modelDir)) { "could not promote ${extractedRoot.path} → ${modelDir.path}" }
    }

    private companion object {
        const val ENV_OVERRIDE = "MOBILEAGENT_VOSK_MODEL"
        const val MODEL_DIR_NAME = "vosk"

        // Pinned small English model from the official Vosk model index
        // (https://alphacephei.com/vosk/models). Small (~40 MB) keeps the first-run
        // download quick; sha256/size are the verified values of the published zip.
        const val ARCHIVE_ROOT = "vosk-model-small-en-us-0.15"
        val SMALL_EN = DesktopModelSpec(
            filename = "$ARCHIVE_ROOT.zip",
            downloadUrl = "https://alphacephei.com/vosk/models/$ARCHIVE_ROOT.zip",
            sha256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498",
            sizeBytes = 41_205_931,
        )
    }
}
