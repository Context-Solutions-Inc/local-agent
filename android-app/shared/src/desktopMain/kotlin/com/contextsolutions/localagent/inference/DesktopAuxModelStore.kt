package com.contextsolutions.localagent.inference

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * First-run acquisition of the desktop ONNX **aux models** (pre-flight classifier +
 * MiniLM embedder), mirroring [DesktopModelStore]/`VoskModelStore`: fetch the file
 * into the app-data `models/` dir, verifying sha256 + size, so [OnnxClassifierEngine]
 * / [OnnxEmbedderEngine] `warmUp()` find it on the next call.
 *
 * Resolution order per model (same precedence as [DesktopAuxModels.classifierModel]):
 *  1. `LOCALAGENT_*_ONNX` env override — operator-managed; used if present, never downloaded.
 *  2. Already on disk in `models/` (size matches) — no-op.
 *  3. A configured hosting endpoint ([DesktopAuxModels.isEndpointConfigured]) — download + verify.
 *  4. Otherwise (placeholder/blank endpoint) — skip; the operator places the `.onnx` by hand.
 *
 * Never throws: a missing endpoint or a failed download just leaves the model absent,
 * and `warmUp()` degrades the agent to Gemma-only / no-op memory (exactly the prior
 * behaviour). The [download] seam is injectable so the decision logic is unit-testable
 * without a real network.
 */
class DesktopAuxModelStore(
    private val baseDir: File = DesktopAppDirs.dataDir(),
    private val download: suspend (DesktopModelInventory, (Long, Long) -> Unit) -> ModelDownloadResult =
        { inventory, onProgress -> DesktopModelDownloader(inventory).download(onProgress) },
    private val logger: (String) -> Unit = {},
) {
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    /** Ensure the pre-flight classifier ONNX is on disk; returns its file or null. */
    suspend fun ensureClassifier(onProgress: (Long, Long) -> Unit = { _, _ -> }): File? =
        ensure(DesktopAuxModels.CLASSIFIER_ENV, DesktopAuxModels.classifierSpec(), onProgress)

    /** Ensure the MiniLM embedder ONNX is on disk; returns its file or null. */
    suspend fun ensureEmbedder(onProgress: (Long, Long) -> Unit = { _, _ -> }): File? =
        ensure(DesktopAuxModels.EMBEDDER_ENV, DesktopAuxModels.embedderSpec(), onProgress)

    internal suspend fun ensure(
        envVar: String,
        spec: DesktopModelSpec,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File? = withContext(ioDispatcher) {
        // (1) Operator-managed override — never download; use the staged file if present.
        System.getenv(envVar)?.takeIf { it.isNotBlank() }?.let { override ->
            val f = File(override)
            return@withContext if (f.isFile) {
                f
            } else {
                logger("$envVar=$override but no file there; ${spec.filename} unavailable")
                null
            }
        }

        val inventory = DesktopModelInventory(spec, baseDir)

        // (2) Already downloaded (size matches) — no-op.
        if (inventory.isPresent()) return@withContext inventory.localFile()

        // (4) No real hosting endpoint configured — skip the fetch, fall back to a
        // manually-placed file if one happens to be there.
        if (!spec.isConfigured) {
            logger(
                "download endpoint not configured for ${spec.filename}; place it in " +
                    "${inventory.modelsDir()} or build with -PauxModelBaseUrl=<host>",
            )
            return@withContext inventory.localFile().takeIf { it.isFile }
        }

        // (3) Download + verify.
        logger("downloading ${spec.filename} (~${spec.sizeBytes / 1_000_000} MB) from ${spec.downloadUrl}")
        when (val result = download(inventory, onProgress)) {
            is ModelDownloadResult.Success -> {
                logger("downloaded ${spec.filename}")
                result.file
            }
            is ModelDownloadResult.Failure -> {
                logger(
                    "download failed for ${spec.filename}: ${result.message} — place it manually in " +
                        "${inventory.modelsDir()} or set -PauxModelBaseUrl",
                )
                null
            }
        }
    }
}
