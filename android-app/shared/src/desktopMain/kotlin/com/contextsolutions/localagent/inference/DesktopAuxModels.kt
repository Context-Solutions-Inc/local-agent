package com.contextsolutions.localagent.inference

import java.io.File
import java.util.Properties

/**
 * On-disk locations + download specs for the desktop **aux models** — the ONNX
 * re-exports of the pre-flight/​memory classifier and the MiniLM embedder (Phase 5,
 * docs/DESKTOP_PORT_PLAN.md). Like the GGUF LLM these are too large to bundle and
 * live in the app-data `models/` dir; the engines tolerate a missing file by
 * returning `null` from `warmUp`, so an absent model just degrades the feature
 * (preflight → Gemma-only; memory → no-op) rather than crashing.
 *
 * Each path has an absolute-path env override so the operator can point straight at
 * a freshly exported `.onnx` (FP32 or `_int8`) without copying it into the app-data
 * dir. When neither the env override nor a downloaded file is present, the
 * [DesktopAuxModelStore] fetches the file from [classifierSpec]/[embedderSpec] —
 * mirroring the GGUF/Vosk first-run download.
 *
 * **Hosting endpoint is compile-time configurable.** The sha256 + byte size below
 * pin the EXACT artifacts (the downloader verifies both), but the hosting URL is
 * supplied by the operator via the `auxModelBaseUrl` Gradle property
 * (`-PauxModelBaseUrl=https://host/path`, or gradle.properties), baked into
 * `desktop_build_info.properties` by :desktopApp and read back by [baseUrl]. Until a
 * real endpoint is set the URL is treated as unconfigured (blank), so the store
 * skips the download and the operator places the `.onnx` manually instead.
 */
object DesktopAuxModels {
    /** Default FP32 export name from `ct-export-onnx --output …v1.0.0.onnx`. */
    const val CLASSIFIER_FILENAME: String = "preflight_memory_shared_v1.0.0.onnx"

    /** Default FP32 export name from `export_minilm_onnx.py`. */
    const val EMBEDDER_FILENAME: String = "all-MiniLM-L6-v2.onnx"

    const val CLASSIFIER_ENV: String = "LOCALAGENT_CLASSIFIER_ONNX"
    const val EMBEDDER_ENV: String = "LOCALAGENT_EMBEDDER_ONNX"

    // Pinned integrity coordinates for the FP32 ONNX exports. The downloader verifies
    // sha256 + size, so a corrupt/truncated transfer is rejected. Re-pin if the model
    // is re-exported (then the hosted artifact must match).
    const val CLASSIFIER_SHA256: String = "8e4e6bcc97715f4323ecf99622bd70a379c735ebcd000bf683d80b7205acfaea"
    const val CLASSIFIER_SIZE_BYTES: Long = 266_092_463L
    const val EMBEDDER_SHA256: String = "b1ccb58ee5c9f4af251cf9f3b4147a883a75441fa24763c002af587f65da28bd"
    const val EMBEDDER_SIZE_BYTES: Long = 91_078_619L

    /**
     * Placeholder hosting base URL — the value baked in when `auxModelBaseUrl` isn't
     * supplied at build time. Treated as "unconfigured" by [isEndpointConfigured] so
     * the store never attempts a doomed fetch; set a real endpoint via
     * `-PauxModelBaseUrl=https://your-host/path` to activate auto-download.
     */
    const val PLACEHOLDER_BASE_URL: String = "https://REPLACE-WITH-HOSTING.invalid/localagent/aux-models/v1.0.0"

    fun classifierModel(baseDir: File = DesktopAppDirs.dataDir()): File =
        resolve(CLASSIFIER_ENV, baseDir, CLASSIFIER_FILENAME)

    fun embedderModel(baseDir: File = DesktopAppDirs.dataDir()): File =
        resolve(EMBEDDER_ENV, baseDir, EMBEDDER_FILENAME)

    /** Download spec for the classifier ONNX. Blank URL ⇒ unconfigured ⇒ no download. */
    fun classifierSpec(): DesktopModelSpec =
        DesktopModelSpec(CLASSIFIER_FILENAME, urlFor(CLASSIFIER_FILENAME), CLASSIFIER_SHA256, CLASSIFIER_SIZE_BYTES)

    /** Download spec for the embedder ONNX. Blank URL ⇒ unconfigured ⇒ no download. */
    fun embedderSpec(): DesktopModelSpec =
        DesktopModelSpec(EMBEDDER_FILENAME, urlFor(EMBEDDER_FILENAME), EMBEDDER_SHA256, EMBEDDER_SIZE_BYTES)

    /** True when a real hosting endpoint was baked in at compile time (not the placeholder). */
    fun isEndpointConfigured(): Boolean = baseUrl().let { it.isNotBlank() && it != PLACEHOLDER_BASE_URL }

    private fun urlFor(filename: String): String =
        if (isEndpointConfigured()) "${baseUrl().trimEnd('/')}/$filename" else ""

    /** Compile-time `auxModelBaseUrl` (from `desktop_build_info.properties`), else the placeholder. */
    private fun baseUrl(): String =
        buildProps.getProperty("auxModelBaseUrl")?.takeIf { it.isNotBlank() } ?: PLACEHOLDER_BASE_URL

    private val buildProps: Properties by lazy {
        Properties().apply {
            DesktopAuxModels::class.java.getResourceAsStream("/desktop_build_info.properties")?.use { load(it) }
        }
    }

    private fun resolve(envVar: String, baseDir: File, filename: String): File {
        val override = System.getenv(envVar)?.takeIf { it.isNotBlank() }
        return if (override != null) File(override) else File(File(baseDir, "models"), filename)
    }
}
