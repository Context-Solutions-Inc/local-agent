package com.contextsolutions.localagent.telemetry

/**
 * Persistence side of the counter pipeline. Implementations snapshot the
 * in-memory recorder state and upsert into the SQLDelight aggregate tables,
 * then clear the in-memory state for the next window.
 *
 * Called from:
 *  - Application lifecycle: `LocalAgentApplication.onStop()` flushes
 *    before the process is killed (Phase C session-end requirement).
 *  - `TelemetryUploader` worker: flushes immediately before reading the
 *    aggregate tables so any in-flight increments make it into the upload.
 *  - Optionally, a periodic timer at UTC-midnight boundaries (deferred —
 *    the worker's 24 h cadence already captures the previous day's window).
 *
 * Separate interface from [TelemetryCounters] so production callsites see
 * only the recording API; nothing in the hot path should be tempted to
 * flush synchronously.
 */
interface TelemetryFlusher {

    /**
     * Drains the in-memory state into the aggregate tables. Idempotent —
     * calling twice in succession produces the second call's no-op flush.
     * Cancellation-safe — partial flushes don't corrupt counters (each
     * counter's increment is independent of others).
     */
    suspend fun flush()
}
