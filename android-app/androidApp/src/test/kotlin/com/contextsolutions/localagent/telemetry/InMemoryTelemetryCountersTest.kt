package com.contextsolutions.localagent.telemetry

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.contextsolutions.localagent.db.LocalAgentDatabase
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase C — telemetry recorder + persistence layer.
 *
 * Covers [InMemoryTelemetryCounters] over a real in-memory SQLite DB
 * (constructed via the v3 schema): increment, tagged increment, latency
 * observation, flush correctness, idempotency, window bucketing across the
 * UTC-midnight boundary, and the contract that the recorder is empty after
 * flush (so the next call doesn't redundantly re-write the same row).
 */
class InMemoryTelemetryCountersTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: LocalAgentDatabase
    private lateinit var nowMs: TestClock

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalAgentDatabase.Schema.create(driver)
        db = LocalAgentDatabase(driver)
        nowMs = TestClock(0L) // exact UTC midnight of 1970-01-01
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun increment_and_flush_persists_to_telemetry_aggregate() = runTest {
        val counters = newCounters()
        counters.increment(CounterNames.QUERIES_TOTAL)
        counters.increment(CounterNames.QUERIES_TOTAL, by = 4)
        counters.increment(CounterNames.PREFLIGHT_HIGH_BAND_TOTAL)
        counters.flush()

        val rows = db.telemetryAggregateQueries
            .selectUnuploadedCountersBefore(window_start_epoch_ms = Long.MAX_VALUE)
            .executeAsList()
            .map { Triple(it.window_start_epoch_ms, it.counter_name, it.counter_value) }
            .sortedBy { it.second }

        assertEquals(2, rows.size)
        assertEquals(Triple(0L, CounterNames.PREFLIGHT_HIGH_BAND_TOTAL, 1L), rows[0])
        assertEquals(Triple(0L, CounterNames.QUERIES_TOTAL, 5L), rows[1])
    }

    @Test
    fun tagged_increment_encodes_name_colon_tag() = runTest {
        val counters = newCounters()
        counters.increment(CounterNames.CONVERSATIONS_DELETED_TOTAL, tag = "expired")
        counters.increment(CounterNames.CONVERSATIONS_DELETED_TOTAL, tag = "expired", by = 2)
        counters.increment(CounterNames.CONVERSATIONS_DELETED_TOTAL, tag = "lru")
        counters.flush()

        val byName = db.telemetryAggregateQueries
            .selectUnuploadedCountersBefore(Long.MAX_VALUE)
            .executeAsList()
            .associate { it.counter_name to it.counter_value }

        assertEquals(3L, byName["${CounterNames.CONVERSATIONS_DELETED_TOTAL}:expired"])
        assertEquals(1L, byName["${CounterNames.CONVERSATIONS_DELETED_TOTAL}:lru"])
        // No "memory_evicted_total" row without a tag — tagged calls always
        // encode as name:tag, never overwrite the bare name.
        assertNull(byName[CounterNames.CONVERSATIONS_DELETED_TOTAL])
    }

    @Test
    fun flush_clears_in_memory_state_so_second_flush_is_noop() = runTest {
        val counters = newCounters()
        counters.increment(CounterNames.QUERIES_TOTAL, by = 7)
        counters.flush()

        // Tamper with the row directly so a second flush would overwrite if
        // the in-memory state hadn't drained.
        db.telemetryAggregateQueries
            .markCounterUploaded(uploaded_at_epoch_ms = 100L, window_start_epoch_ms = 0L, counter_name = CounterNames.QUERIES_TOTAL)

        counters.flush() // second flush — must NOT write anything

        val rows = db.telemetryAggregateQueries
            .selectUnuploadedCountersBefore(Long.MAX_VALUE)
            .executeAsList()
        assertTrue("second flush leaked rows: $rows", rows.isEmpty())
    }

    @Test
    fun increment_accumulates_across_two_calls_within_same_window() = runTest {
        val counters = newCounters()
        counters.increment(CounterNames.PREFLIGHT_HIGH_BAND_TOTAL)
        counters.increment(CounterNames.PREFLIGHT_HIGH_BAND_TOTAL)
        counters.increment(CounterNames.PREFLIGHT_HIGH_BAND_TOTAL)
        counters.flush()

        val row = db.telemetryAggregateQueries
            .selectUnuploadedCountersBefore(Long.MAX_VALUE)
            .executeAsList()
            .single()
        assertEquals(3L, row.counter_value)
        assertEquals(0L, row.window_start_epoch_ms)
    }

    @Test
    fun increments_in_different_windows_get_separate_rows() = runTest {
        val counters = newCounters()
        // Day 0 (window_start_epoch_ms = 0).
        nowMs.set(12 * 60 * 60 * 1000L) // noon, day 0
        counters.increment(CounterNames.QUERIES_TOTAL)
        counters.increment(CounterNames.QUERIES_TOTAL)
        // Day 1 (window_start_epoch_ms = 86_400_000).
        nowMs.set(36 * 60 * 60 * 1000L) // noon, day 1
        counters.increment(CounterNames.QUERIES_TOTAL)
        counters.flush()

        val rows = db.telemetryAggregateQueries
            .selectUnuploadedCountersBefore(Long.MAX_VALUE)
            .executeAsList()
            .sortedBy { it.window_start_epoch_ms }

        assertEquals(2, rows.size)
        assertEquals(0L, rows[0].window_start_epoch_ms)
        assertEquals(2L, rows[0].counter_value)
        assertEquals(86_400_000L, rows[1].window_start_epoch_ms)
        assertEquals(1L, rows[1].counter_value)
    }

    @Test
    fun observe_latency_persists_p50_p95_p99_and_sample_count() = runTest {
        val counters = newCounters()
        // Inject deterministic samples 1..200. p50≈100, p95≈190, p99≈198.
        for (ms in 1L..200L) counters.observeLatency(LatencyNames.FIRST_TOKEN_MS, ms)
        counters.flush()

        val row = db.telemetryAggregateQueries
            .selectUnuploadedLatenciesBefore(Long.MAX_VALUE)
            .executeAsList()
            .single()

        assertEquals(LatencyNames.FIRST_TOKEN_MS, row.metric_name)
        assertEquals(200L, row.sample_count)
        // Nearest-rank percentile across a sorted [1..200]:
        //   p50 = index ceil(0.50 * 200) - 1 = 99 → value 100
        //   p95 = index ceil(0.95 * 200) - 1 = 189 → value 190
        //   p99 = index ceil(0.99 * 200) - 1 = 197 → value 198
        assertEquals(100L, row.p50_ms)
        assertEquals(190L, row.p95_ms)
        assertEquals(198L, row.p99_ms)
    }

    @Test
    fun reservoir_caps_at_1024_samples_but_keeps_sample_count_accurate() = runTest {
        val counters = newCounters(random = Random(42))
        // Push 5000 samples — reservoir holds only 1024, but sample_count
        // must still report the true total.
        for (i in 0L until 5000L) counters.observeLatency(LatencyNames.SEARCH_MS, i)
        counters.flush()

        val row = db.telemetryAggregateQueries
            .selectUnuploadedLatenciesBefore(Long.MAX_VALUE)
            .executeAsList()
            .single()
        assertEquals(5000L, row.sample_count)
        // Percentiles are estimated from the 1024-sample reservoir; with
        // seed 42 and a uniform stream, p50 lands within ±15% of the true
        // 2499. Use a generous tolerance — this test guards against the
        // reservoir going degenerate, not against estimator accuracy.
        assertTrue("p50=${row.p50_ms} outside [2000, 3000]", row.p50_ms in 2000L..3000L)
    }

    @Test
    fun empty_flush_writes_nothing() = runTest {
        val counters = newCounters()
        counters.flush()
        assertEquals(
            0,
            db.telemetryAggregateQueries.selectUnuploadedCountersBefore(Long.MAX_VALUE).executeAsList().size,
        )
        assertEquals(
            0,
            db.telemetryAggregateQueries.selectUnuploadedLatenciesBefore(Long.MAX_VALUE).executeAsList().size,
        )
    }

    @Test
    fun later_flush_does_not_revive_uploaded_counter() = runTest {
        val counters = newCounters()
        counters.increment(CounterNames.QUERIES_TOTAL, by = 3)
        counters.flush()

        // Upload step (simulating TelemetryUploader behavior).
        db.telemetryAggregateQueries.markCounterUploaded(
            uploaded_at_epoch_ms = 100L,
            window_start_epoch_ms = 0L,
            counter_name = CounterNames.QUERIES_TOTAL,
        )

        // Subsequent increments in the same window upsert — the upsert
        // increments counter_value but DOES NOT touch uploaded_at_epoch_ms
        // (see TelemetryAggregate.sq). The uploader's "unuploaded" query
        // filters on NULL uploaded_at_epoch_ms; an already-uploaded row
        // with a fresh delta will be skipped on the next upload (the row
        // stays marked uploaded). The follow-up increment is effectively
        // dropped on the floor — acceptable: we'd rather miss a delta than
        // double-count, and the volume per window is bounded.
        counters.increment(CounterNames.QUERIES_TOTAL, by = 4)
        counters.flush()

        // The upserted row carries counter_value=7 but uploaded_at_epoch_ms=100
        // (not NULL'd by the upsert). selectUnuploadedCountersBefore returns
        // empty.
        val unuploaded = db.telemetryAggregateQueries
            .selectUnuploadedCountersBefore(Long.MAX_VALUE)
            .executeAsList()
        assertTrue("expected no unuploaded rows, got $unuploaded", unuploaded.isEmpty())
    }

    // -- Helpers ---------------------------------------------------------------

    private fun newCounters(random: Random = Random(42)): InMemoryTelemetryCounters =
        InMemoryTelemetryCounters(
            queries = db.telemetryAggregateQueries,
            nowEpochMs = nowMs::get,
            random = random,
            ioDispatcher = Dispatchers.Unconfined,
        )

    private class TestClock(initial: Long) {
        private var current = initial
        fun get(): Long = current
        fun set(value: Long) { current = value }
    }
}
