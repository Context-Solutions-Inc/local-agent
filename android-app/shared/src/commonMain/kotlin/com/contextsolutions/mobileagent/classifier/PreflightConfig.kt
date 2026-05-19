package com.contextsolutions.mobileagent.classifier

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Versioned, JSON-serializable bundle of pre-flight tunable parameters.
 * Shipped at `:androidApp/src/main/assets/preflight_config.json`, parsed at
 * app start. Per PRD §3.2.1 thresholds are configurable so post-launch
 * telemetry-driven tuning can ship via app update without code change.
 */
@Serializable
data class PreflightConfig(
    @SerialName("model_version")
    val modelVersion: String,

    @SerialName("thresholds")
    val thresholds: PreflightThresholds,
) {
    companion object {
        /** Default values bundled at v1.0 ship — see model card § Threshold defaults. */
        val DEFAULT: PreflightConfig = PreflightConfig(
            modelVersion = "preflight_memory_shared_v1.0.0",
            thresholds = PreflightThresholds.DEFAULT,
        )
    }
}

/**
 * Three-band routing thresholds for `p_search_required` (= softmax of
 * preflight_logits at index [ClassifierOutput.PREFLIGHT_INDEX_SEARCH_REQUIRED]).
 *
 *  - `> highBand` → fire pre-flight search
 *  - `< lowBand`  → skip search (Gemma still has the tool registered)
 *  - otherwise    → fall through to Gemma's own tool-call decision
 */
@Serializable
data class PreflightThresholds(
    @SerialName("high_band")
    val highBand: Float,

    @SerialName("low_band")
    val lowBand: Float,
) {
    init {
        require(lowBand in 0f..1f) { "lowBand must be in [0, 1], was $lowBand" }
        require(highBand in 0f..1f) { "highBand must be in [0, 1], was $highBand" }
        require(lowBand < highBand) {
            "lowBand ($lowBand) must be strictly less than highBand ($highBand)"
        }
    }

    companion object {
        /**
         * Per `docs/M3_M4_HANDOFF.md` §4 (originally 0.85). The high band
         * was relaxed to 0.5 after on-device testing showed the v1.0
         * classifier was under-firing on weather/sports queries that the
         * vertical adapters can handle cleanly. The asset bundle in
         * `preflight_config.json` is the source of truth at runtime; this
         * default fires only when the asset fails to load.
         */
        val DEFAULT: PreflightThresholds = PreflightThresholds(
            highBand = 0.5f,
            lowBand = 0.15f,
        )
    }
}
