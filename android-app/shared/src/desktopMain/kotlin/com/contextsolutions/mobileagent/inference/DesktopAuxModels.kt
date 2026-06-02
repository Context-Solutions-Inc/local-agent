package com.contextsolutions.mobileagent.inference

import java.io.File

/**
 * On-disk locations of the desktop **aux models** — the ONNX re-exports of the
 * pre-flight/​memory classifier and the MiniLM embedder (Phase 5,
 * docs/DESKTOP_PORT_PLAN.md). Like the GGUF LLM these are too large to bundle
 * and live in the app-data `models/` dir (the download wiring + the full
 * `ResourceLoader` asset abstraction are Phase 6); the engines tolerate a
 * missing file by returning `null` from `warmUp`, so an absent model just
 * degrades the feature rather than crashing.
 *
 * Each path has an absolute-path env override so the operator can point straight
 * at a freshly exported `.onnx` (FP32 or `_int8`) without copying it into the
 * app-data dir — the same "simple path/env for now" the plan sanctions for
 * Phase 5. Mirrors [DesktopModelInventory] (the GGUF) but trivially, since aux
 * models aren't yet downloaded/verified through [DesktopModelDownloader].
 */
object DesktopAuxModels {
    /** Default FP32 export name from `ct-export-onnx --output …v1.0.0.onnx`. */
    const val CLASSIFIER_FILENAME: String = "preflight_memory_shared_v1.0.0.onnx"

    /** Default FP32 export name from `export_minilm_onnx.py`. */
    const val EMBEDDER_FILENAME: String = "all-MiniLM-L6-v2.onnx"

    const val CLASSIFIER_ENV: String = "MOBILEAGENT_CLASSIFIER_ONNX"
    const val EMBEDDER_ENV: String = "MOBILEAGENT_EMBEDDER_ONNX"

    fun classifierModel(baseDir: File = DesktopAppDirs.dataDir()): File =
        resolve(CLASSIFIER_ENV, baseDir, CLASSIFIER_FILENAME)

    fun embedderModel(baseDir: File = DesktopAppDirs.dataDir()): File =
        resolve(EMBEDDER_ENV, baseDir, EMBEDDER_FILENAME)

    private fun resolve(envVar: String, baseDir: File, filename: String): File {
        val override = System.getenv(envVar)?.takeIf { it.isNotBlank() }
        return if (override != null) File(override) else File(File(baseDir, "models"), filename)
    }
}
