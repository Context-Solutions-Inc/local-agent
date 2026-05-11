package com.contextsolutions.mobileagent.telemetry

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Production [AnalyticsSink] backed by Firebase Analytics. Auto-initialized
 * via the `google-services` Gradle plugin reading
 * `androidApp/google-services.json` at build time.
 *
 * Failure isolation: `FirebaseAnalytics.logEvent` is non-throwing in practice
 * (the SDK swallows errors and batches; this wrapper double-belts with a
 * catch). Failed telemetry MUST NEVER surface to the user — that's both a
 * UX requirement and a privacy-first invariant (a user who opted into
 * anonymous counters shouldn't see error UI when our backend is flaky).
 */
class FirebaseAnalyticsSink(context: Context) : AnalyticsSink {

    private val analytics = FirebaseAnalytics.getInstance(context.applicationContext)

    override fun send(event: AnalyticsSink.AnalyticsEvent) {
        try {
            val bundle = Bundle(event.params.size).apply {
                event.params.forEach { (key, value) -> putLong(key, value) }
            }
            analytics.logEvent(event.name, bundle)
        } catch (t: Throwable) {
            // Best-effort: a telemetry failure is never user-visible. Log a
            // counter — IF we had a telemetry counter for telemetry failures,
            // which would be circular — so just record at debug level for
            // local diagnosis.
            Log.w(TAG, "Failed to log ${event.name}: ${t.message}")
        }
    }

    private companion object {
        const val TAG = "TelemetrySink"
    }
}
