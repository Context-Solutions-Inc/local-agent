package com.contextsolutions.mobileagent.telemetry

/**
 * Egress chokepoint for telemetry events. The production [TelemetryUploader]
 * dispatches every event through this interface — never through
 * `FirebaseAnalytics` directly — so that:
 *
 *  - Tests can substitute a recording fake without an Android Context or
 *    a real Firebase project.
 *  - Phase D (Crashlytics) can introduce a second sink (or a fanout) with
 *    no change at the call site.
 *  - Future analytics destinations (BigQuery, custom backend) become a
 *    one-implementation drop-in.
 *
 * The wire format mirrors Firebase Analytics constraints (25 parameters
 * per event, 100-char key, 100-char string value) so that [send] inputs
 * can flow straight through without further normalization. The themed
 * events are documented in `M6_PLAN.md §3.3.2`.
 */
interface AnalyticsSink {

    /**
     * Send one event with up to ~25 parameters. Implementations MUST be
     * non-blocking from the caller's perspective (Firebase analytics
     * batches internally; the call returns immediately). Errors must be
     * absorbed silently — failed telemetry never surfaces to the user.
     */
    fun send(event: AnalyticsEvent)

    /** Event name + parameters. Long-shaped values map to Firebase params via toString. */
    data class AnalyticsEvent(
        val name: String,
        val params: Map<String, Long>,
    )
}
