package com.contextsolutions.localagent.memory

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Versioned, JSON-serializable bundle of memory-extraction tunable
 * parameters. Shipped at `:androidApp/src/main/assets/memory_config.json`,
 * parsed at app start. Mirrors `PreflightConfig` in shape so post-launch
 * telemetry-driven tuning can ship via app update without code change.
 */
@Serializable
data class MemoryConfig(
    @SerialName("model_version")
    val modelVersion: String,

    @SerialName("thresholds")
    val thresholds: MemoryThresholds,

    /**
     * Hard cap on the number of (non-expired) memories the store will hold.
     * Enforced at save time by [MemoryExtractor]: a save that would exceed
     * this is refused and the UI surfaces a "limit reached" dialog (memories
     * are only ever removed by explicit user action — no auto-eviction).
     *
     * Keeps the per-turn `[MEMORY]` prompt block bounded (~maxMemories × ~10
     * tokens). Defaults to 100 (~1,000 tokens). Has a JSON default so older
     * assets without the key still parse.
     */
    @SerialName("max_memories")
    val maxMemories: Int = DEFAULT_MAX_MEMORIES,
) {
    init {
        require(maxMemories >= 1) { "max_memories must be >= 1, was $maxMemories" }
    }

    companion object {
        const val DEFAULT_MAX_MEMORIES: Int = 100

        val DEFAULT: MemoryConfig = MemoryConfig(
            modelVersion = "preflight_memory_shared_v1.0.0",
            thresholds = MemoryThresholds.DEFAULT,
            maxMemories = DEFAULT_MAX_MEMORIES,
        )
    }
}

/**
 * Two-band routing thresholds for `p_has_extraction`
 * (= softmax(presenceLogits) at
 * [com.contextsolutions.localagent.classifier.ClassifierOutput.PRESENCE_INDEX_HAS_EXTRACTION]):
 *
 *  - `>= ask` → surface a Save / Dismiss card to the user
 *  - otherwise → silent skip
 *
 * Pre-PR#7 a third "high band" auto-saved at `>= autoSave`. Removed so
 * every classifier-driven save passes through explicit user consent —
 * Lawrence's UX call: the model is not confident enough at v1.0 to
 * justify silent writes, and the prompt card is cheap. Explicit
 * `RememberForgetDetector.Command.Remember` still auto-saves (the user
 * literally typed "remember …").
 *
 * [category] is the existing multi-label sigmoid cutoff used to decide
 * which `MemoryCategory` heads are active for a given turn.
 */
@Serializable
data class MemoryThresholds(
    @SerialName("ask")
    val ask: Float,

    @SerialName("category")
    val category: Float,
) {
    init {
        require(ask in 0f..1f) { "ask must be in [0, 1], was $ask" }
        require(category in 0f..1f) { "category must be in [0, 1], was $category" }
    }

    companion object {
        /**
         * Defaults. PR #26 raised `ask` from 0.15 (PRD §3.2.1 low band) to
         * 0.6 — above the presence head's argmax operating point (0.5,
         * model card: 92.2% precision) for an extra margin against
         * false-positive save prompts on marginal turns. Still
         * configurable via `memory_config.json`.
         */
        val DEFAULT: MemoryThresholds = MemoryThresholds(
            ask = 0.6f,
            category = 0.5f,
        )
    }
}
