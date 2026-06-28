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
 * **Hosting endpoint defaults to the CDN, overridable at compile time.** The sha256
 * + byte size below pin the EXACT artifacts (the downloader verifies both). The
 * hosting URL defaults to [DEFAULT_BASE_URL] (the models.contextsolutions.com CDN, so
 * a normal build auto-downloads on first run); an operator can still point at a different host via
 * the `auxModelBaseUrl` Gradle property (`-PauxModelBaseUrl=https://host/path`, or
 * gradle.properties), baked into `desktop_build_info.properties` by :desktopApp and
 * read back by [baseUrl] (PR #3).
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
     * Default hosting base URL — the models.contextsolutions.com CDN that serves the
     * aux models, used when `auxModelBaseUrl` isn't supplied at build time (PR #3). A normal build is
     * therefore "configured" ([isEndpointConfigured]) and auto-downloads on first
     * run; override with `-PauxModelBaseUrl=https://your-host/path` to point elsewhere.
     */
    const val DEFAULT_BASE_URL: String = "https://models.contextsolutions.com"

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

    /** True when a non-blank hosting endpoint is configured (default CDN or a build override). */
    fun isEndpointConfigured(): Boolean = baseUrl().isNotBlank()

    private fun urlFor(filename: String): String =
        if (isEndpointConfigured()) "${baseUrl().trimEnd('/')}/$filename" else ""

    /** Compile-time `auxModelBaseUrl` (from `desktop_build_info.properties`), else [DEFAULT_BASE_URL]. */
    private fun baseUrl(): String =
        buildProps.getProperty("auxModelBaseUrl")?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL

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
