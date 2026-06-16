package com.contextsolutions.localagent.memory

/**
 * On-device sentence embedder. v1 is `all-MiniLM-L6-v2` INT8 weight-only
 * (~25 MB, 384-dim output). Loaded lazily on chat-screen entry the same
 * way the classifier engine is — `ChatViewModel.init` kicks off [warmUp]
 * on a background coroutine so load latency hides behind user typing time.
 *
 * **Threading.** [warmUp], [embed], and [unload] MUST be safe to call from
 * a background coroutine and MUST NOT block the main thread. The Android
 * implementation wraps in `withContext(Dispatchers.IO)` per CLAUDE.md inv. #1.
 *
 * **Failure mode.** Embedder load failure is non-fatal: [warmUp] returns
 * `null`, [embed] returns `null`, and the upstream `MemoryRetriever` /
 * `MemoryExtractor` degrade to no-op (PRD §3.2.4 — "memory is an
 * enhancement layer that must degrade gracefully").
 *
 * Mirrors the [ClassifierEngine] surface so the two engines feel
 * interchangeable in tests and Hilt wiring.
 */
interface EmbedderEngine {

    /** True once [warmUp] has succeeded; subsequent [embed] calls run inference. */
    val isLoaded: Boolean

    /**
     * Load the model + delegate. Idempotent: a second call on a loaded
     * engine is a no-op. Returns the chosen accelerator on success or
     * `null` on failure — failures must NOT throw.
     */
    suspend fun warmUp(): EmbedderAccelerator?

    /**
     * Embed a single string. Tokenisation is internal — callers pass raw text.
     * Returns the L2-normalised 384-dim vector or `null` if the engine is not
     * loaded or the inference call fails.
     */
    suspend fun embed(text: String): EmbedderOutput?

    /** Release the model + delegate resources. Safe to call when not loaded. */
    suspend fun unload()
}

/** Accelerator selected at load time. Surfaced for debug UI / logging. */
enum class EmbedderAccelerator {
    GPU,
    CPU,
}
