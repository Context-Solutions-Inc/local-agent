package com.contextsolutions.mobileagent.memory

/**
 * Memory categories per PRD §3.2.4 / `CLASSIFIER_DATASETS.md` §3.3.
 *
 * **Ordering is load-bearing.** The enum order MUST match the
 * `category_logits` index contract on the shipped classifier
 * (`preflight_memory_shared_v1.0.0_int8.tflite`). Reordering this enum
 * silently swaps memory categories at extraction time. The Python source
 * of truth is `classifier_training/src/classifier_training/datasets/schemas.py`
 * → `MemoryCategory`; the contract is also documented in
 * `docs/M4_M5_HANDOFF.md` §3 and `ClassifierOutput`'s KDoc.
 */
enum class MemoryCategory(val wireName: String) {
    PERSONAL_IDENTITY("personal_identity"),
    PREFERENCE("preference"),
    PROFESSIONAL("professional"),
    INTEREST("interest"),
    RELATIONSHIP("relationship"),
    TEMPORARY_CONTEXT("temporary_context"),
    ;

    companion object {
        /** Resolve from the wire form used in datasets / DB rows. */
        fun fromWireName(name: String): MemoryCategory? =
            entries.firstOrNull { it.wireName == name }

        /** Index in the classifier `category_logits` output (0..5). */
        fun fromCategoryIndex(index: Int): MemoryCategory? =
            entries.getOrNull(index)
    }
}
