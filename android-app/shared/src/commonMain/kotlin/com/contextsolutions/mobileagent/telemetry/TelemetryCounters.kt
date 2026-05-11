package com.contextsolutions.mobileagent.telemetry

/**
 * Counter-only telemetry surface per PRD §3.2.1 + §4.4.
 *
 * **Counter-only contract:** implementations record numeric counts and
 * latency samples ONLY. They MUST NOT accept or persist strings derived
 * from user content (queries, memory text, conversation excerpts). The
 * `name` and `tag` parameters here are SHIPPED, FIXED, ENUM-LIKE values
 * declared at call sites (see [CounterNames] / [LatencyNames]) — never
 * computed from runtime data. M6 Phase C unit tests pin these to a known
 * set and fail if a new dynamic counter slips in.
 *
 * The hot-path calls ([increment] / [observeLatency]) are non-suspending
 * and lock-free in the production implementation; production sites can
 * record telemetry on every code path without measurable overhead.
 *
 * Persistence + uploading is driven elsewhere (see `TelemetryFlushScheduler`
 * + `TelemetryUploader` worker); this interface is purely the recording
 * side of the pipeline.
 */
interface TelemetryCounters {

    /**
     * Increment a named counter by [by] (default 1) within the current UTC
     * day window. Cheap: a single atomic add against an in-memory bucket.
     */
    fun increment(name: String, by: Long = 1)

    /**
     * Increment a named counter with a tag (e.g. `memory_evicted_total` tagged
     * by tier `expired` / `stale` / `lru`). The implementation joins
     * `name:tag` into a single counter key per the schema's encoding.
     */
    fun increment(name: String, tag: String, by: Long = 1) =
        increment("$name:$tag", by)

    /**
     * Record a latency observation for a named metric. The implementation
     * reservoir-samples the last ~1024 observations per metric per day and
     * emits p50/p95/p99 + sample_count at flush time.
     */
    fun observeLatency(metric: String, durationMs: Long)

    companion object {
        /**
         * Reservoir capacity per latency metric per day. 1024 samples is
         * sufficient for stable p99 estimation at our query volumes
         * (Pixel 7 + single user is bounded well under 1024 turns/day).
         */
        const val LATENCY_RESERVOIR_SIZE: Int = 1024
    }
}

/**
 * Stable counter-name constants. Production callsites import from here so
 * the wire-format names are visible in one place. Tagged counters list the
 * accepted tag values inline; passing anything else is a contract violation
 * the unit-test suite catches.
 */
object CounterNames {
    // daily_inference
    const val QUERIES_TOTAL = "queries_total"
    const val INFERENCE_WARMUP_LOADED_TOTAL = "inference_warmup_loaded_total"
    const val INFERENCE_WARMUP_ALREADY_LOADED_TOTAL = "inference_warmup_already_loaded_total"
    const val INFERENCE_WARMUP_ALREADY_LOADING_TOTAL = "inference_warmup_already_loading_total"
    const val INFERENCE_WARMUP_SKIPPED_THERMAL_TOTAL = "inference_warmup_skipped_thermal_total"
    const val INFERENCE_WARMUP_FAILED_TOTAL = "inference_warmup_failed_total"
    const val INFERENCE_UNLOADED_IDLE_TOTAL = "inference_unloaded_idle_total"
    const val INFERENCE_UNLOADED_TRIM_MEMORY_TOTAL = "inference_unloaded_trim_memory_total"
    /**
     * Fires when the app's MainThreadHeartbeatWatchdog tripped and forced a
     * model unload to avert a system-server-watchdog soft reboot. See
     * [com.contextsolutions.mobileagent.observability.MainThreadHeartbeatWatchdog].
     * Counted alongside [MAIN_THREAD_WATCHDOG_TRIPPED_TOTAL] — every trip
     * increments both, but separating them lets us see "watchdog ran but
     * model wasn't actually loaded" cases (forceUnload no-ops).
     */
    const val INFERENCE_UNLOADED_WATCHDOG_TOTAL = "inference_unloaded_watchdog_total"
    /**
     * Standalone counter for the watchdog firing, independent of whether a
     * model was actually loaded at the time. Diagnostic for "how often is
     * the app hitting a >20s main-thread stall" in the wild.
     */
    const val MAIN_THREAD_WATCHDOG_TRIPPED_TOTAL = "main_thread_watchdog_tripped_total"

    // daily_preflight
    const val PREFLIGHT_HIGH_BAND_TOTAL = "preflight_high_band_total"
    const val PREFLIGHT_MIDDLE_BAND_TOTAL = "preflight_middle_band_total"
    const val PREFLIGHT_LOW_BAND_TOTAL = "preflight_low_band_total"
    const val PREFLIGHT_REWRITER_ABORT_TOTAL = "preflight_rewriter_abort_total"
    const val PREFLIGHT_CLASSIFIER_UNAVAILABLE_TOTAL = "preflight_classifier_unavailable_total"

    // daily_search
    const val SEARCH_INVOKED_TOTAL = "search_invoked_total"
    const val SEARCH_CACHE_HIT_TOTAL = "search_cache_hit_total"
    /** Tag values: `network` | `timeout` | `client_error` | `server_error` | `unexpected`. */
    const val SEARCH_ERROR_TOTAL = "search_error_total"
    const val SEARCH_DISABLED_TOTAL = "search_disabled_total"
    const val SEARCH_NO_KEY_TOTAL = "search_no_key_total"

    // daily_memory
    const val MEMORY_EXTRACTED_TOTAL = "memory_extracted_total"
    const val MEMORY_DEDUP_SKIPPED_TOTAL = "memory_dedup_skipped_total"
    const val MEMORY_FORGOTTEN_TOTAL = "memory_forgotten_total"
    /** Tag values: `expired` | `stale` | `lru`. */
    const val MEMORY_EVICTED_TOTAL = "memory_evicted_total"
    const val MEMORY_RETRIEVED_TOTAL = "memory_retrieved_total"
    const val MEMORY_CREATION_DISABLED_TOTAL = "memory_creation_disabled_total"
}

/** Stable latency-metric constants. Same contract as [CounterNames]. */
object LatencyNames {
    const val FIRST_TOKEN_MS = "first_token_ms"
    const val PREFLIGHT_MS = "preflight_ms"
    const val SEARCH_MS = "search_ms"
    const val MEMORY_RETRIEVAL_MS = "memory_retrieval_ms"
}
