package com.contextsolutions.localagent.telemetry

import com.contextsolutions.localagent.db.TelemetryAggregateQueries

/**
 * Drains the in-memory recorder into SQL, builds Firebase event payloads,
 * dispatches them through the [AnalyticsSink], and marks the rows uploaded.
 *
 * Lifecycle: invoked by `TelemetryUploadWorker` on a 24h cadence under the
 * UNMETERED constraint (PRD §4.4 — no metered-data egress for opt-in
 * telemetry; M6_PLAN §3.3 Phase C step 5).
 *
 * Consent gate: every fire reads [TelemetryConsentManager.enabled]. When
 * the user toggles OFF mid-window, this method returns [Outcome.SkippedConsent]
 * on the next fire without touching the SQL queries or AnalyticsSink. Rows
 * accumulated before the toggle stay in the DB unsent — Phase E adds a
 * "Clear telemetry data" Settings button that purges them.
 */
class TelemetryUploader(
    private val consent: TelemetryConsentManager,
    private val flusher: TelemetryFlusher,
    private val builder: TelemetryPayloadBuilder,
    private val sink: AnalyticsSink,
    private val queries: TelemetryAggregateQueries,
    private val nowEpochMs: () -> Long,
) {

    /**
     * One upload pass. Idempotent — calling twice in sequence sends the
     * first pass's batch and then a no-op (everything is marked uploaded).
     * Cancellation-safe in the WorkManager sense: SQL writes are
     * transactional per row, so a partial run leaves the DB in a recoverable
     * state. Worst case: a marked-but-not-sent row would be skipped on the
     * next pass — acceptable (we prefer drop-on-uncertain over double-send).
     *
     * @param includeCurrentWindow when true, today's still-open UTC window
     *   is also transmitted. PRODUCTION CALLERS SHOULD LEAVE THIS FALSE.
     *   Once today's partial counts are sent and marked uploaded, any
     *   further increments in the same window are dropped on subsequent
     *   uploads (the upsert path doesn't reset uploaded_at_epoch_ms).
     *   The debug "Run telemetry upload now" button passes true so a
     *   developer can verify the pipeline end-to-end without waiting
     *   until UTC midnight; periodic worker passes false.
     */
    suspend fun upload(includeCurrentWindow: Boolean = false): Outcome {
        if (!consent.enabled()) return Outcome.SkippedConsent

        // Drain the in-memory recorder so the SQL aggregate tables reflect
        // every counter accumulated since the last flush. Without this the
        // upload would miss anything that landed since the last session-end
        // flush (which only fires on app stop).
        flusher.flush()

        // Production: only upload windows STRICTLY before today's UTC
        // midnight (today is still open — we don't want partial counts).
        // Debug: include today by setting the cutoff to "after today" so
        // the SQL `< cutoff` predicate accepts today's rows too.
        val cutoff = if (includeCurrentWindow) {
            Long.MAX_VALUE
        } else {
            TimeWindow.windowStartOf(nowEpochMs())
        }
        val payload = builder.build(windowCutoffEpochMs = cutoff)
        if (payload.events.isEmpty()) return Outcome.Empty

        for (event in payload.events) sink.send(event)

        // Mark rows uploaded so the next pass doesn't re-send them. The
        // upsertCounter query is additive within a window (excluded
        // uploaded_at_epoch_ms from its update so this mark persists across
        // future increments).
        val uploadedAt = nowEpochMs()
        queries.transaction {
            for (marker in payload.markers) {
                when (marker.source) {
                    TelemetryPayloadBuilder.Marker.Source.Counter ->
                        queries.markCounterUploaded(
                            uploaded_at_epoch_ms = uploadedAt,
                            window_start_epoch_ms = marker.windowStartEpochMs,
                            counter_name = marker.name,
                        )
                    TelemetryPayloadBuilder.Marker.Source.Latency ->
                        queries.markLatencyUploaded(
                            uploaded_at_epoch_ms = uploadedAt,
                            window_start_epoch_ms = marker.windowStartEpochMs,
                            metric_name = marker.name,
                        )
                }
            }
        }

        return Outcome.Sent(eventCount = payload.events.size)
    }

    sealed interface Outcome {
        /** User has opted out — no work done. */
        data object SkippedConsent : Outcome

        /** Consent ON but no closed windows had pending data. */
        data object Empty : Outcome

        /** Sent [eventCount] events; rows marked uploaded. */
        data class Sent(val eventCount: Int) : Outcome
    }
}
