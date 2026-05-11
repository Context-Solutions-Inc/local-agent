package com.contextsolutions.mobileagent.telemetry

/**
 * No-op [TelemetryCounters]. Used as a default in production constructors so
 * unit tests that construct components directly don't have to supply a fake.
 * Production wiring is via Hilt — the singleton `InMemoryTelemetryCounters`
 * overrides the default. Tests that want to assert counter calls use
 * `RecordingTelemetryCounters` instead.
 */
object NoOpTelemetryCounters : TelemetryCounters {
    override fun increment(name: String, by: Long) = Unit
    override fun observeLatency(metric: String, durationMs: Long) = Unit
}
