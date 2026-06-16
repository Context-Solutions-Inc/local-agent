package com.contextsolutions.localagent.telemetry

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.contextsolutions.localagent.db.LocalAgentDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase C — end-to-end uploader test.
 *
 * Wires the real [TelemetryPayloadBuilder] + [InMemoryTelemetryCounters] +
 * SQLDelight DB; substitutes a fake [TelemetryConsentManager] (so we can
 * toggle consent mid-test) and a recording [AnalyticsSink] (so we can
 * inspect what was sent).
 *
 * Covers the four contract bullets in M6_PLAN §4 Phase C exit gate:
 *   - Toggle ON → events sent.
 *   - Toggle OFF → no events sent.
 *   - Rows marked uploaded so next pass is empty.
 *   - Open window (today) not transmitted.
 */
class TelemetryUploaderTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: LocalAgentDatabase
    private lateinit var consent: FakeConsent
    private lateinit var sink: RecordingSink
    private lateinit var counters: InMemoryTelemetryCounters
    private lateinit var nowMs: TestClock

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalAgentDatabase.Schema.create(driver)
        db = LocalAgentDatabase(driver)
        consent = FakeConsent(initial = true)
        sink = RecordingSink()
        nowMs = TestClock(initial = 86_400_000L) // start of day 1 — anything in day 0 is "yesterday"
        counters = InMemoryTelemetryCounters(
            queries = db.telemetryAggregateQueries,
            nowEpochMs = nowMs::get,
        )
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun uploads_closed_window_when_consent_is_enabled() = runTest {
        // Seed day 0's window directly so the in-memory counter has nothing
        // to flush; the uploader's path is "flush + read + send".
        db.telemetryAggregateQueries.upsertCounter(
            window_start_epoch_ms = 0L,
            counter_name = CounterNames.QUERIES_TOTAL,
            counter_value = 5,
        )

        val outcome = newUploader().upload()
        assertTrue("expected Sent, got $outcome", outcome is TelemetryUploader.Outcome.Sent)
        assertEquals(1, sink.events.size)
        assertEquals(TelemetryPayloadBuilder.EVENT_DAILY_INFERENCE, sink.events.single().name)
        assertEquals(5L, sink.events.single().params[CounterNames.QUERIES_TOTAL])
    }

    @Test
    fun second_upload_pass_does_not_resend_already_marked_rows() = runTest {
        db.telemetryAggregateQueries.upsertCounter(
            window_start_epoch_ms = 0L,
            counter_name = CounterNames.QUERIES_TOTAL,
            counter_value = 3,
        )

        val uploader = newUploader()
        val first = uploader.upload()
        assertTrue(first is TelemetryUploader.Outcome.Sent)
        sink.events.clear()

        val second = uploader.upload()
        assertEquals(TelemetryUploader.Outcome.Empty, second)
        assertTrue("second pass leaked events: ${sink.events}", sink.events.isEmpty())
    }

    @Test
    fun consent_off_returns_skipped_and_sends_nothing() = runTest {
        db.telemetryAggregateQueries.upsertCounter(
            window_start_epoch_ms = 0L,
            counter_name = CounterNames.QUERIES_TOTAL,
            counter_value = 7,
        )
        consent.setEnabled(false)

        val outcome = newUploader().upload()
        assertEquals(TelemetryUploader.Outcome.SkippedConsent, outcome)
        assertTrue("consent-off must not send: ${sink.events}", sink.events.isEmpty())
        // SQL row preserved (not marked uploaded) so a future opt-in could
        // still send it — caller decides via the Phase E "clear data" button
        // whether that's acceptable.
        val rows = db.telemetryAggregateQueries
            .selectUnuploadedCountersBefore(Long.MAX_VALUE)
            .executeAsList()
        assertEquals(1, rows.size)
    }

    @Test
    fun open_window_is_not_transmitted() = runTest {
        // Seed today's window — the upload should NOT carry this row.
        nowMs.set(86_400_000L + 12 * 60 * 60 * 1000L) // noon, day 1
        db.telemetryAggregateQueries.upsertCounter(
            window_start_epoch_ms = 86_400_000L, // today's window
            counter_name = CounterNames.QUERIES_TOTAL,
            counter_value = 4,
        )

        val outcome = newUploader().upload()
        assertEquals(TelemetryUploader.Outcome.Empty, outcome)
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun flush_then_send_picks_up_in_memory_counters() = runTest {
        // Increment in day 0's window so the cutoff (start of day 1)
        // includes it after flush.
        nowMs.set(12 * 60 * 60 * 1000L) // noon, day 0
        counters.increment(CounterNames.PREFLIGHT_HIGH_BAND_TOTAL, by = 2)
        // Switch to day 1 so the cutoff (start of day 1 = 86_400_000) treats
        // day 0's bucket as closed.
        nowMs.set(86_400_000L)

        val outcome = newUploader().upload()
        assertTrue(outcome is TelemetryUploader.Outcome.Sent)
        val event = sink.events.single { it.name == TelemetryPayloadBuilder.EVENT_DAILY_PREFLIGHT }
        assertEquals(2L, event.params[CounterNames.PREFLIGHT_HIGH_BAND_TOTAL])
    }

    @Test
    fun include_current_window_uploads_todays_open_bucket() = runTest {
        // Increment in today's still-open window. Default upload would
        // skip this; the debug flag includes it.
        nowMs.set(86_400_000L + 12 * 60 * 60 * 1000L) // noon, day 1
        counters.increment(CounterNames.QUERIES_TOTAL, by = 4)

        val defaultOutcome = newUploader().upload()
        assertEquals(
            "default upload must skip today's open window",
            TelemetryUploader.Outcome.Empty,
            defaultOutcome,
        )
        assertTrue(sink.events.isEmpty())

        val overrideOutcome = newUploader().upload(includeCurrentWindow = true)
        assertTrue(
            "debug override must send today's data: got $overrideOutcome",
            overrideOutcome is TelemetryUploader.Outcome.Sent,
        )
        val event = sink.events.single { it.name == TelemetryPayloadBuilder.EVENT_DAILY_INFERENCE }
        assertEquals(4L, event.params[CounterNames.QUERIES_TOTAL])
        assertEquals(86_400_000L, event.params["window_start_epoch_ms"])
    }

    // -- Helpers ---------------------------------------------------------------

    private fun newUploader(): TelemetryUploader = TelemetryUploader(
        consent = consent,
        flusher = counters,
        builder = TelemetryPayloadBuilder(db.telemetryAggregateQueries),
        sink = sink,
        queries = db.telemetryAggregateQueries,
        nowEpochMs = nowMs::get,
    )

    private class FakeConsent(initial: Boolean) : TelemetryConsentManager {
        private val enabledState = MutableStateFlow(initial)
        private val firstRunState = MutableStateFlow(true)
        override fun enabled(): Boolean = enabledState.value
        override fun enabledFlow(): Flow<Boolean> = enabledState.asStateFlow()
        override fun setEnabled(enabled: Boolean) { enabledState.value = enabled }
        override fun firstRunDecided(): Boolean = firstRunState.value
        override fun firstRunDecidedFlow(): Flow<Boolean> = firstRunState.asStateFlow()
        override fun markFirstRunDecided() { firstRunState.value = true }
    }

    private class RecordingSink : AnalyticsSink {
        val events = mutableListOf<AnalyticsSink.AnalyticsEvent>()
        override fun send(event: AnalyticsSink.AnalyticsEvent) { events += event }
    }

    private class TestClock(initial: Long) {
        private var current = initial
        fun get(): Long = current
        fun set(value: Long) { current = value }
    }
}
