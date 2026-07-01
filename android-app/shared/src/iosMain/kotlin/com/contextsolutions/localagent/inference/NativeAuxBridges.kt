package com.contextsolutions.localagent.inference

/**
 * Swift→Kotlin bridges for the on-device **auxiliary** models on iOS (Phase 2):
 * the pre-flight/memory **classifier** and the MiniLM **embedder**.
 *
 * Like [NativeLlmBridge], iOS has no Kotlin/Native runtime for these `.onnx`
 * graphs, so the **Swift** app implements these interfaces on **ONNX Runtime**
 * (`onnxruntime-objc`/CoreML EP + CPU fallback) and registers the instance into
 * Koin via `doInitKoin(...)`. The Kotlin adapters [OnnxIosClassifierEngine] /
 * [OnnxIosEmbedderEngine] wrap them into the `ClassifierEngine` / `EmbedderEngine`
 * seams (suspend), so `PreflightRouter` / `MemoryRetriever` / `MemoryExtractor` are
 * unchanged.
 *
 * **Pure-numeric + callback-shaped (no suspend / Flow).** Kotlin owns WordPiece
 * tokenization (the shared [com.contextsolutions.localagent.classifier.WordPieceTokenizer])
 * and passes `input_ids` / `attention_mask` (int64, length 128) across; Swift stays
 * "dumb" — it only runs the graph and returns the raw float outputs. There is **no
 * streaming and no cancel handle** (a forward pass is single-shot), the one place
 * this diverges from [NativeLlmBridge].
 *
 * The Swift side MUST honor the same named-IO contract the desktop ONNX engines use
 * (`OnnxClassifierEngine` / `OnnxEmbedderEngine`): inputs `input_ids` /
 * `attention_mask`; classifier outputs `preflight_logits` `[1,3]`, `presence_logits`
 * `[1,2]`, `category_logits` `[1,6]` read by name; embedder = its single output
 * `[1,384]`. The Kotlin adapters re-validate sizes via `ClassifierOutput` /
 * `EmbedderOutput` `init`, so an export that drifts the signature degrades (returns
 * null) rather than corrupting downstream state.
 */
interface NativeClassifierBridge {

    /**
     * Load the classifier `.onnx` at [modelPath]. [useGpu] requests the CoreML
     * execution provider (the impl falls back to CPU on failure). Exactly one of
     * [onLoaded] (with the accelerator actually used — `"gpu"`/`"cpu"`) or [onError]
     * is invoked.
     */
    fun load(
        modelPath: String,
        useGpu: Boolean,
        onLoaded: (accelerator: String) -> Unit,
        onError: (message: String) -> Unit,
    )

    /**
     * Run one forward pass. [inputIds] + [attentionMask] are int64, length 128.
     * Exactly one of [onResult] (the three head vectors, sizes 3/2/6) or [onError]
     * is invoked. May fire on a background queue.
     */
    fun classify(
        inputIds: LongArray,
        attentionMask: LongArray,
        onResult: (preflightLogits: FloatArray, presenceLogits: FloatArray, categoryLogits: FloatArray) -> Unit,
        onError: (message: String) -> Unit,
    )

    /** Release the ONNX session. Safe to call repeatedly. */
    fun unload()
}

interface NativeEmbedderBridge {

    /** Load the embedder `.onnx` at [modelPath]. Mirrors [NativeClassifierBridge.load]. */
    fun load(
        modelPath: String,
        useGpu: Boolean,
        onLoaded: (accelerator: String) -> Unit,
        onError: (message: String) -> Unit,
    )

    /**
     * Run one forward pass. [inputIds] + [attentionMask] are int64, length 128.
     * Exactly one of [onResult] (the 384-dim L2-normalized sentence vector) or
     * [onError] is invoked. May fire on a background queue.
     */
    fun embed(
        inputIds: LongArray,
        attentionMask: LongArray,
        onResult: (vector: FloatArray) -> Unit,
        onError: (message: String) -> Unit,
    )

    /** Release the ONNX session. Safe to call repeatedly. */
    fun unload()
}
