package com.contextsolutions.localagent.app.service

/**
 * Download coordinates for the Android **aux models** — the INT8 `.tflite`
 * re-exports of the pre-flight/memory classifier and the MiniLM embedder.
 *
 * PR #3: these used to be bundled into the APK at build time (~88 MB). They now
 * download from the CDN on first run (folded into the same first-run download
 * gate as Gemma), mirroring the desktop [com.contextsolutions.localagent.inference.DesktopAuxModels]
 * path. The filenames match what [com.contextsolutions.localagent.classifier.LiteRtClassifierEngine]
 * / [com.contextsolutions.localagent.memory.LiteRtEmbedderEngine] look for under
 * `filesDir/models/`.
 *
 * The sha256 + byte size pin the EXACT artifacts (the downloader verifies both),
 * so a corrupt/truncated transfer is rejected. Re-pin on a deliberate re-export
 * (and re-upload the hosted artifact to match). No auth — the CDN is public.
 */
object AndroidAuxModels {
    /** R2 CDN base; shared with the desktop aux models + GGUF mirror. */
    const val BASE_URL: String = "https://pub-f6c21df457bd434ebe799585697ff4b6.r2.dev"

    const val CLASSIFIER_FILENAME: String = "preflight_memory_shared_v1.0.0_int8.tflite"
    const val EMBEDDER_FILENAME: String = "all-MiniLM-L6-v2_int8.tflite"

    val CLASSIFIER_SPEC: ModelSpec = ModelSpec(
        filename = CLASSIFIER_FILENAME,
        downloadUrl = "$BASE_URL/$CLASSIFIER_FILENAME",
        sha256 = "5920733f96bfc2f193fdebc7ef5585cd37ecc3b9f23b21259e448410679ea83d",
        sizeBytes = 67_688_256L,
    )

    val EMBEDDER_SPEC: ModelSpec = ModelSpec(
        filename = EMBEDDER_FILENAME,
        downloadUrl = "$BASE_URL/$EMBEDDER_FILENAME",
        sha256 = "d4320c6f082450d542949ca1067cbc82de4c0c4c4f2ff8915752ff0885c55dcb",
        sizeBytes = 23_536_088L,
    )

    /** Both aux models, in load order (classifier then embedder). */
    val SPECS: List<ModelSpec> = listOf(CLASSIFIER_SPEC, EMBEDDER_SPEC)
}
