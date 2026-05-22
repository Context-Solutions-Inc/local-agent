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
     * Fires when [com.contextsolutions.mobileagent.app.observability.MemoryPressureWatchdog]
     * observes `availMem` below the proactive threshold (1 GiB) and unloads
     * Gemma before another app's allocation pressure pushes the device into
     * LMK / system_server reclaim. PR #16.
     */
    const val INFERENCE_UNLOADED_LOW_MEMORY_TOTAL = "inference_unloaded_low_memory_total"
    /**
     * Standalone counter for the watchdog firing, independent of whether a
     * model was actually loaded at the time. Diagnostic for "how often is
     * the app hitting a >20s main-thread stall" in the wild.
     */
    const val MAIN_THREAD_WATCHDOG_TRIPPED_TOTAL = "main_thread_watchdog_tripped_total"

    // daily_preflight — classifier engine lifecycle (PR #8). Mirror the
    // Gemma INFERENCE_* counters above. The classifier hosts the preflight
    // head + memory presence + memory category heads in one .tflite, so
    // these lifecycle counters live alongside the preflight band counters
    // below rather than being split across daily_inference / daily_memory.
    const val CLASSIFIER_WARMUP_LOADED_TOTAL = "classifier_warmup_loaded_total"
    const val CLASSIFIER_WARMUP_ALREADY_LOADED_TOTAL = "classifier_warmup_already_loaded_total"
    const val CLASSIFIER_WARMUP_SKIPPED_THERMAL_TOTAL = "classifier_warmup_skipped_thermal_total"
    const val CLASSIFIER_WARMUP_FAILED_TOTAL = "classifier_warmup_failed_total"
    const val CLASSIFIER_UNLOADED_IDLE_TOTAL = "classifier_unloaded_idle_total"
    const val CLASSIFIER_UNLOADED_TRIM_MEMORY_TOTAL = "classifier_unloaded_trim_memory_total"
    const val CLASSIFIER_UNLOADED_WATCHDOG_TOTAL = "classifier_unloaded_watchdog_total"

    // daily_preflight
    const val PREFLIGHT_HIGH_BAND_TOTAL = "preflight_high_band_total"
    const val PREFLIGHT_MIDDLE_BAND_TOTAL = "preflight_middle_band_total"
    const val PREFLIGHT_LOW_BAND_TOTAL = "preflight_low_band_total"
    const val PREFLIGHT_REWRITER_ABORT_TOTAL = "preflight_rewriter_abort_total"
    const val PREFLIGHT_CLASSIFIER_UNAVAILABLE_TOTAL = "preflight_classifier_unavailable_total"

    // A FireSearch the band thresholds alone would NOT have produced — forced
    // because RelativeTemporalDetector matched a now-relative phrase. A subset
    // of PREFLIGHT_HIGH_BAND_TOTAL; lets us watch how often temporal force-fire
    // carries the decision (and its false-positive rate). See invariant #38.
    const val PREFLIGHT_TEMPORAL_FORCE_TOTAL = "preflight_temporal_force_total"

    // daily_search
    const val SEARCH_INVOKED_TOTAL = "search_invoked_total"
    const val SEARCH_CACHE_HIT_TOTAL = "search_cache_hit_total"
    /** Tag values: `network` | `timeout` | `client_error` | `server_error` | `unexpected`. */
    const val SEARCH_ERROR_TOTAL = "search_error_total"
    const val SEARCH_DISABLED_TOTAL = "search_disabled_total"
    const val SEARCH_NO_KEY_TOTAL = "search_no_key_total"

    // daily_memory — embedder engine lifecycle (PR #8). Same shape as the
    // classifier counters above; the embedder only feeds the memory
    // subsystem so it routes to daily_memory.
    const val EMBEDDER_WARMUP_LOADED_TOTAL = "embedder_warmup_loaded_total"
    const val EMBEDDER_WARMUP_ALREADY_LOADED_TOTAL = "embedder_warmup_already_loaded_total"
    const val EMBEDDER_WARMUP_SKIPPED_THERMAL_TOTAL = "embedder_warmup_skipped_thermal_total"
    const val EMBEDDER_WARMUP_FAILED_TOTAL = "embedder_warmup_failed_total"
    const val EMBEDDER_UNLOADED_IDLE_TOTAL = "embedder_unloaded_idle_total"
    const val EMBEDDER_UNLOADED_TRIM_MEMORY_TOTAL = "embedder_unloaded_trim_memory_total"
    const val EMBEDDER_UNLOADED_WATCHDOG_TOTAL = "embedder_unloaded_watchdog_total"

    // daily_memory
    /**
     * Rollup of every memory insertion. The auto / prompted split below
     * sums to this — kept as-is for backward compatibility with M6 Phase C
     * dashboards.
     */
    const val MEMORY_EXTRACTED_TOTAL = "memory_extracted_total"

    /**
     * Auto-saves driven by an explicit `RememberForgetDetector.Command.Remember`
     * ("remember that …"). Pre-PR#7 this also covered the high-band
     * classifier path; PR#7 removed that band so every classifier-driven
     * save now flows through `MEMORY_EXTRACTED_PROMPTED_TOTAL` via a user
     * consent card. Counter name kept stable for dashboard continuity.
     */
    const val MEMORY_EXTRACTED_AUTO_TOTAL = "memory_extracted_auto_total"

    /** Saves driven by the user tapping Save on a classifier-path prompt card. */
    const val MEMORY_EXTRACTED_PROMPTED_TOTAL = "memory_extracted_prompted_total"

    /** Classifier-path prompt cards rendered to the user (PR#7: includes former high band). */
    const val MEMORY_PROMPT_SHOWN_TOTAL = "memory_prompt_shown_total"

    /** User tapped Save on a classifier-path prompt card. */
    const val MEMORY_PROMPT_ACCEPTED_TOTAL = "memory_prompt_accepted_total"

    /**
     * User tapped Dismiss OR a subsequent turn auto-dismissed an unanswered
     * card. Combined into a single counter at v1; split into `_explicit` /
     * `_auto` later if telemetry shows the question is interesting.
     */
    const val MEMORY_PROMPT_DISMISSED_TOTAL = "memory_prompt_dismissed_total"

    const val MEMORY_DEDUP_SKIPPED_TOTAL = "memory_dedup_skipped_total"
    const val MEMORY_FORGOTTEN_TOTAL = "memory_forgotten_total"

    // daily_conversation — PR#13. Conversation-lifecycle counters: created,
    // resumed (user tapped a row in the history list), deleted (tagged
    // `explicit` for user-initiated, `capacity` for the 50-conversation cap),
    // and the two overflow signals (warned-once-per-conv + turn-pair drop).
    const val CONVERSATIONS_CREATED_TOTAL = "conversations_created_total"
    const val CONVERSATIONS_RESUMED_TOTAL = "conversations_resumed_total"
    /** Tag values: `explicit` | `capacity`. */
    const val CONVERSATIONS_DELETED_TOTAL = "conversations_deleted_total"
    const val CONVERSATION_OVERFLOW_WARNED_TOTAL = "conversation_overflow_warned_total"
    const val CONVERSATION_TURNPAIRS_DROPPED_TOTAL = "conversation_turnpairs_dropped_total"

    /** User tapped Export on the memory screen and the file was written. */
    const val MEMORY_EXPORTED_TOTAL = "memory_exported_total"

    /** User tapped Import and the override-and-restore completed (any row count, including 0). */
    const val MEMORY_IMPORTED_TOTAL = "memory_imported_total"
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
