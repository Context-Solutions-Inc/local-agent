package com.contextsolutions.localagent.telemetry

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.contextsolutions.localagent.db.LocalAgentDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase C — payload builder.
 *
 * Two test families:
 *
 *  1. **Routing correctness** — counters and latencies land in the right
 *     themed event (`daily_inference` / `daily_preflight` / `daily_search`
 *     / `daily_memory`) per the prefix-based partitioning.
 *
 *  2. **Memory-exclusion contract (PRD §4.4 + WS-12)** — the builder MUST
 *     NOT carry any string borrowed from the `memories` table. We seed the
 *     DB with a memory containing a unique marker string, build the
 *     payload, and assert the marker doesn't appear anywhere in the event
 *     stream. This is the load-bearing privacy gate; failing it blocks
 *     Phase C release.
 */
class TelemetryPayloadBuilderTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: LocalAgentDatabase
    private lateinit var builder: TelemetryPayloadBuilder

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalAgentDatabase.Schema.create(driver)
        db = LocalAgentDatabase(driver)
        builder = TelemetryPayloadBuilder(db.telemetryAggregateQueries)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    // ── Routing correctness ──────────────────────────────────────────────────

    @Test
    fun routes_counters_into_themed_events_by_prefix() = runTest {
        seedCounter(0L, CounterNames.QUERIES_TOTAL, 5)
        seedCounter(0L, CounterNames.PREFLIGHT_HIGH_BAND_TOTAL, 2)
        seedCounter(0L, CounterNames.SEARCH_INVOKED_TOTAL, 3)
        seedCounter(0L, CounterNames.MEMORY_EXTRACTED_TOTAL, 1)

        val output = builder.build(windowCutoffEpochMs = Long.MAX_VALUE)

        val byName = output.events.associateBy { it.name }
        assertEquals(4, byName.size)
        assertEquals(5L, byName[TelemetryPayloadBuilder.EVENT_DAILY_INFERENCE]?.params?.get(CounterNames.QUERIES_TOTAL))
        assertEquals(2L, byName[TelemetryPayloadBuilder.EVENT_DAILY_PREFLIGHT]?.params?.get(CounterNames.PREFLIGHT_HIGH_BAND_TOTAL))
        assertEquals(3L, byName[TelemetryPayloadBuilder.EVENT_DAILY_SEARCH]?.params?.get(CounterNames.SEARCH_INVOKED_TOTAL))
        assertEquals(1L, byName[TelemetryPayloadBuilder.EVENT_DAILY_MEMORY]?.params?.get(CounterNames.MEMORY_EXTRACTED_TOTAL))
    }

    @Test
    fun latency_percentiles_are_emitted_with_named_suffixes() = runTest {
        seedLatency(0L, LatencyNames.FIRST_TOKEN_MS, p50 = 100, p95 = 200, p99 = 250, count = 10)

        val output = builder.build(Long.MAX_VALUE)
        val params = output.events.single { it.name == TelemetryPayloadBuilder.EVENT_DAILY_INFERENCE }.params

        assertEquals(100L, params["${LatencyNames.FIRST_TOKEN_MS}_p50_ms"])
        assertEquals(200L, params["${LatencyNames.FIRST_TOKEN_MS}_p95_ms"])
        assertEquals(250L, params["${LatencyNames.FIRST_TOKEN_MS}_p99_ms"])
        assertEquals(10L, params["${LatencyNames.FIRST_TOKEN_MS}_sample_count"])
    }

    @Test
    fun window_start_is_attached_to_every_event() = runTest {
        seedCounter(86_400_000L, CounterNames.QUERIES_TOTAL, 7)

        val output = builder.build(Long.MAX_VALUE)
        val event = output.events.single()
        assertEquals(86_400_000L, event.params["window_start_epoch_ms"])
    }

    @Test
    fun separate_windows_produce_separate_events() = runTest {
        seedCounter(0L, CounterNames.QUERIES_TOTAL, 3)
        seedCounter(86_400_000L, CounterNames.QUERIES_TOTAL, 5)

        val output = builder.build(Long.MAX_VALUE)
        val events = output.events.filter { it.name == TelemetryPayloadBuilder.EVENT_DAILY_INFERENCE }
        assertEquals(2, events.size)
        // Sorted by window in the builder; assert deterministic ordering.
        assertEquals(0L, events[0].params["window_start_epoch_ms"])
        assertEquals(86_400_000L, events[1].params["window_start_epoch_ms"])
        assertEquals(3L, events[0].params[CounterNames.QUERIES_TOTAL])
        assertEquals(5L, events[1].params[CounterNames.QUERIES_TOTAL])
    }

    @Test
    fun cutoff_filters_out_open_window() = runTest {
        // Yesterday's window — should be included.
        seedCounter(0L, CounterNames.QUERIES_TOTAL, 4)
        // Today's window (above cutoff) — should be excluded.
        seedCounter(86_400_000L, CounterNames.QUERIES_TOTAL, 9)

        val output = builder.build(windowCutoffEpochMs = 86_400_000L)
        val events = output.events.filter { it.name == TelemetryPayloadBuilder.EVENT_DAILY_INFERENCE }
        assertEquals(1, events.size)
        assertEquals(0L, events[0].params["window_start_epoch_ms"])
        assertEquals(4L, events[0].params[CounterNames.QUERIES_TOTAL])
    }

    @Test
    fun empty_db_returns_empty_output() = runTest {
        val output = builder.build(Long.MAX_VALUE)
        assertEquals(0, output.events.size)
        assertEquals(0, output.markers.size)
    }

    @Test
    fun markers_match_one_to_one_with_emitted_rows() = runTest {
        seedCounter(0L, CounterNames.QUERIES_TOTAL, 1)
        seedCounter(0L, CounterNames.MEMORY_EXTRACTED_TOTAL, 1)
        seedLatency(0L, LatencyNames.SEARCH_MS, p50 = 50, p95 = 100, p99 = 150, count = 5)

        val output = builder.build(Long.MAX_VALUE)
        // 2 counter markers + 1 latency marker = 3 markers.
        assertEquals(3, output.markers.size)
        assertTrue(output.markers.any { it.name == CounterNames.QUERIES_TOTAL && it.source == TelemetryPayloadBuilder.Marker.Source.Counter })
        assertTrue(output.markers.any { it.name == CounterNames.MEMORY_EXTRACTED_TOTAL && it.source == TelemetryPayloadBuilder.Marker.Source.Counter })
        assertTrue(output.markers.any { it.name == LatencyNames.SEARCH_MS && it.source == TelemetryPayloadBuilder.Marker.Source.Latency })
    }

    // ── Memory-exclusion guard (load-bearing privacy gate) ───────────────────

    @Test
    fun payload_never_contains_memory_text_even_when_memories_table_is_populated() = runTest {
        // Seed the memories table with a UNIQUE MARKER string. If any code
        // path in the builder accidentally reads the memories table, the
        // marker will land in some event parameter; this assertion catches
        // that. PRD §4.4 + WS-12.
        val marker = "MEMORY_LEAK_CANARY_${Long.MAX_VALUE}"
        seedMemory(text = marker)

        // Seed legitimate telemetry data so the builder has SOMETHING to
        // emit (an empty payload is trivially safe but doesn't exercise the
        // serialization path).
        seedCounter(0L, CounterNames.MEMORY_EXTRACTED_TOTAL, 3)
        seedCounter(0L, CounterNames.MEMORY_RETRIEVED_TOTAL, 2)
        seedLatency(0L, LatencyNames.MEMORY_RETRIEVAL_MS, p50 = 50, p95 = 70, p99 = 100, count = 5)

        val output = builder.build(Long.MAX_VALUE)
        assertFalse("payload must not be empty for this test to be meaningful", output.events.isEmpty())

        for (event in output.events) {
            assertFalse(
                "Event name contained memory marker: ${event.name}",
                event.name.contains(marker),
            )
            for ((key, value) in event.params) {
                assertFalse(
                    "Event param key contained memory marker: $key",
                    key.contains(marker),
                )
                assertFalse(
                    "Event param value contained memory marker: $value",
                    value.toString().contains(marker),
                )
            }
        }
    }

    @Test
    fun payload_never_contains_message_content_even_when_messages_table_is_populated() = runTest {
        // Same guard for the `messages` table — Phase C must not bridge
        // any conversation content into telemetry.
        val marker = "MESSAGE_LEAK_CANARY_42"
        seedMessage(content = marker)

        seedCounter(0L, CounterNames.QUERIES_TOTAL, 5)
        val output = builder.build(Long.MAX_VALUE)
        for (event in output.events) {
            for ((_, value) in event.params) {
                assertFalse(
                    "Event param value contained message marker: $value",
                    value.toString().contains(marker),
                )
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun seedCounter(windowStart: Long, name: String, value: Long) {
        db.telemetryAggregateQueries.upsertCounter(
            window_start_epoch_ms = windowStart,
            counter_name = name,
            counter_value = value,
        )
    }

    private fun seedLatency(
        windowStart: Long,
        metric: String,
        p50: Long,
        p95: Long,
        p99: Long,
        count: Long,
    ) {
        db.telemetryAggregateQueries.upsertLatency(
            window_start_epoch_ms = windowStart,
            metric_name = metric,
            p50_ms = p50,
            p95_ms = p95,
            p99_ms = p99,
            sample_count = count,
        )
    }

    private fun seedMemory(text: String) {
        // Encode the marker into the memories table directly via raw SQL —
        // we don't want a memory-store dep in this test.
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO memories(
                    id, text, category, conversation_id,
                    created_at_epoch_ms, last_accessed_epoch_ms,
                    embedding, expires_at_epoch_ms, access_count
                ) VALUES ('m1', '$text', 'preference', NULL,
                          1700000000000, 1700000000000,
                          X'00000000', NULL, 0);
            """.trimIndent(),
            parameters = 0,
        )
    }

    private fun seedMessage(content: String) {
        // Seed a parent conversation first (FK constraint).
        driver.execute(
            identifier = null,
            sql = "INSERT INTO conversations(id, title, created_at_epoch_ms, updated_at_epoch_ms) " +
                "VALUES ('c1', 'test', 1700000000000, 1700000000000);",
            parameters = 0,
        )
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO messages(id, conversation_id, role, content,
                                     tool_call_json, tool_result_json,
                                     created_at_epoch_ms, sequence_index)
                VALUES ('msg1', 'c1', 'user', '$content',
                        NULL, NULL, 1700000000000, 0);
            """.trimIndent(),
            parameters = 0,
        )
    }
}
