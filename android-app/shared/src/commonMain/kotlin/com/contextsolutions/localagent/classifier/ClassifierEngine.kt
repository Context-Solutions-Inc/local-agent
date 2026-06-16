package com.contextsolutions.localagent.classifier

/**
 * Single-forward-pass DistilBERT-class classifier. Backed by the shared
 * `preflight_memory_shared_v1.0.0_int8.tflite` artifact (M3 ship target),
 * which exposes three task heads in one inference:
 *
 *  - preflight (3-class softmax) — used by [PreflightRouter] for the M4
 *    routing decision per PRD §3.2.1.
 *  - memory presence (2-class softmax) — consumed by M5 / WS-9.
 *  - memory category (6-way multi-label sigmoid) — consumed by M5.
 *
 * Per `docs/M3_M4_HANDOFF.md` §2 the .tflite is fixed at sequence length 128
 * with int64 inputs (`input_ids`, `attention_mask`). Output ordering is the
 * trace-time order — [ClassifierOutput] documents the contract; the actual
 * implementation must verify it at load via interpreter metadata
 * (CLAUDE.md hard invariant #12).
 *
 * **Threading.** `loadModel`, `classify`, and `unload` MUST be safe to call
 * from a background coroutine and MUST NOT block the main thread. The
 * Android implementation is expected to wrap into `withContext(Dispatchers.IO)`
 * (CLAUDE.md hard invariant #1).
 *
 * **Lifecycle.** Per M4_PLAN §4 Phase B, the engine is loaded lazily on
 * chat-screen entry (`ChatViewModel.init` calls [warmUp] on a background
 * coroutine). Once loaded it stays resident for the app lifetime. Failure
 * to load is reported as a non-throwing return value so the upstream
 * [PreflightRouter] can degrade gracefully (PRD §3.2.1 failure modes).
 */
interface ClassifierEngine {

    /** True once [warmUp] has succeeded; subsequent [classify] calls run inference. */
    val isLoaded: Boolean

    /**
     * Load the model and any delegate state. Idempotent: a second call on a
     * loaded engine is a no-op. Returns the chosen accelerator on success or
     * `null` on failure — failures must NOT throw, since classifier
     * unavailability is a defined degradation mode (the agent falls back to
     * standard Gemma tool-calling).
     */
    suspend fun warmUp(): ClassifierAccelerator?

    /**
     * Run a single forward pass on tokenized input. Caller is responsible for
     * tokenization (see [WordPieceTokenizer]). Returns `null` if the engine
     * is not loaded or the inference call fails.
     *
     * @param inputIds shape `[seqLen]`, padded with [WordPieceTokenizer.PAD_ID].
     * @param attentionMask shape `[seqLen]`, 1 for real tokens, 0 for padding.
     */
    suspend fun classify(inputIds: LongArray, attentionMask: LongArray): ClassifierOutput?

    /** Release the model and delegate resources. Safe to call when not loaded. */
    suspend fun unload()
}

/** Accelerator selected at load time. Surfaced for debug UI / logging. */
enum class ClassifierAccelerator {
    GPU,
    CPU,
}
