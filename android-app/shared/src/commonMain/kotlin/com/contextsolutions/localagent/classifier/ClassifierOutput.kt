package com.contextsolutions.localagent.classifier

/**
 * Raw logits from a single classifier forward pass.
 *
 * The .tflite emits all three heads simultaneously; M4 consumes only
 * [preflightLogits], M5 will consume [presenceLogits] and [categoryLogits].
 * Each head is identified at load time by parsing the trailing `:N` from
 * the interpreter tensor's `name()` (per CLAUDE.md hard invariant #12 —
 * Play Services LiteRT permutes the interpreter index away from the name
 * suffix, so dispatching by index ships a silent head swap):
 *
 *  - `StatefulPartitionedCall:0` → [preflightLogits], shape `[3]`,
 *    order `[search_required, search_not_required, ambiguous]`.
 *  - `StatefulPartitionedCall:1` → [presenceLogits], shape `[2]`,
 *    order `[no_extraction, has_extraction]`.
 *  - `StatefulPartitionedCall:2` → [categoryLogits], shape `[6]`,
 *    order `[personal_identity, preference, professional, interest,
 *    relationship, temporary_context]`.
 *
 * Each head's shape is unique on this graph, so the engine should also
 * verify shape after name-based dispatch as a defense against future
 * re-exports that might change the per-head dimensionality.
 *
 * Apply softmax to [preflightLogits] / [presenceLogits], sigmoid to
 * [categoryLogits] (multi-label, threshold 0.5).
 */
data class ClassifierOutput(
    val preflightLogits: FloatArray,
    val presenceLogits: FloatArray,
    val categoryLogits: FloatArray,
) {
    init {
        require(preflightLogits.size == PREFLIGHT_CLASSES) {
            "preflightLogits must be size $PREFLIGHT_CLASSES, was ${preflightLogits.size}"
        }
        require(presenceLogits.size == PRESENCE_CLASSES) {
            "presenceLogits must be size $PRESENCE_CLASSES, was ${presenceLogits.size}"
        }
        require(categoryLogits.size == CATEGORY_CLASSES) {
            "categoryLogits must be size $CATEGORY_CLASSES, was ${categoryLogits.size}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClassifierOutput) return false
        return preflightLogits.contentEquals(other.preflightLogits) &&
            presenceLogits.contentEquals(other.presenceLogits) &&
            categoryLogits.contentEquals(other.categoryLogits)
    }

    override fun hashCode(): Int {
        var result = preflightLogits.contentHashCode()
        result = 31 * result + presenceLogits.contentHashCode()
        result = 31 * result + categoryLogits.contentHashCode()
        return result
    }

    companion object {
        const val PREFLIGHT_CLASSES: Int = 3
        const val PRESENCE_CLASSES: Int = 2
        const val CATEGORY_CLASSES: Int = 6

        const val PREFLIGHT_INDEX_SEARCH_REQUIRED: Int = 0
        const val PREFLIGHT_INDEX_SEARCH_NOT_REQUIRED: Int = 1
        const val PREFLIGHT_INDEX_AMBIGUOUS: Int = 2

        const val PRESENCE_INDEX_NO_EXTRACTION: Int = 0
        const val PRESENCE_INDEX_HAS_EXTRACTION: Int = 1
    }
}
