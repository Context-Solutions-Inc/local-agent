package com.contextsolutions.localagent.telemetry

import com.contextsolutions.localagent.db.TelemetryAggregateQueries
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Production [TelemetryCounters] + [TelemetryFlusher].
 *
 * Recording (hot path): each [increment] / [observeLatency] is a single
 * ConcurrentHashMap lookup plus an atomic add (counters) or a synchronized
 * reservoir update (latencies). No allocations in the steady state once a
 * counter / metric is first seen.
 *
 * Persistence: [flush] snapshots the entire in-memory map, hands the
 * snapshot to the SQLDelight upsert queries on [ioDispatcher], then atomically
 * resets the in-memory state. Atomicity is guaranteed by [flushMutex] —
 * only one flush runs at a time; concurrent increments during a flush land
 * in the newly-empty maps the flush left behind, attributing to the same
 * (current) UTC-day window.
 *
 * Window boundary handling: counters are bucketed by the UTC-day window at
 * RECORD time (not flush time), so a counter incremented before midnight
 * UTC and flushed after midnight UTC still attributes to the correct day.
 */
class InMemoryTelemetryCounters(
    private val queries: TelemetryAggregateQueries,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    private val random: Random = Random.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TelemetryCounters, TelemetryFlusher {

    /** Key = (windowStartEpochMs, name); value = atomic count. */
    private val counters = ConcurrentHashMap<WindowedKey, AtomicLong>()

    /** Key = (windowStartEpochMs, metric); value = reservoir sampler. */
    private val latencies = ConcurrentHashMap<WindowedKey, ReservoirSampler>()

    private val flushMutex = Mutex()

    override fun increment(name: String, by: Long) {
        val key = WindowedKey(TimeWindow.windowStartOf(nowEpochMs()), name)
        counters
            .computeIfAbsent(key) { AtomicLong(0) }
            .addAndGet(by)
    }

    override fun observeLatency(metric: String, durationMs: Long) {
        val key = WindowedKey(TimeWindow.windowStartOf(nowEpochMs()), metric)
        latencies
            .computeIfAbsent(key) { ReservoirSampler(TelemetryCounters.LATENCY_RESERVOIR_SIZE, random) }
            .observe(durationMs)
    }

    /**
     * Snapshot + persist + clear. Done under [flushMutex] so two concurrent
     * flushes serialize cleanly (the second one sees the maps emptied by
     * the first and writes nothing). Increments racing with the flush are
     * safe: ConcurrentHashMap.computeIfAbsent atomically re-creates a fresh
     * counter for the same window key after the snapshot drained it.
     */
    override suspend fun flush(): Unit = flushMutex.withLock {
        // Drain into local maps so the in-memory state is empty as quickly
        // as possible — concurrent increments during the SQL upserts land
        // in fresh entries and will be picked up by the next flush.
        val counterSnapshot = HashMap<WindowedKey, Long>(counters.size)
        val latencySnapshot = HashMap<WindowedKey, LatencySnapshot>(latencies.size)

        val counterIterator = counters.entries.iterator()
        while (counterIterator.hasNext()) {
            val entry = counterIterator.next()
            // getAndSet(0) on the AtomicLong keeps the entry in the map but
            // drains its value — safer than entry-removal-then-recreate
            // which races with computeIfAbsent.
            val drained = entry.value.getAndSet(0)
            if (drained != 0L) counterSnapshot[entry.key] = drained
        }
        val latencyIterator = latencies.entries.iterator()
        while (latencyIterator.hasNext()) {
            val entry = latencyIterator.next()
            val snap = entry.value.snapshotAndReset()
            if (snap.sampleCount > 0) latencySnapshot[entry.key] = snap
        }

        if (counterSnapshot.isEmpty() && latencySnapshot.isEmpty()) return@withLock

        withContext(ioDispatcher) {
            queries.transaction {
                counterSnapshot.forEach { (key, value) ->
                    queries.upsertCounter(
                        window_start_epoch_ms = key.windowStartEpochMs,
                        counter_name = key.name,
                        counter_value = value,
                    )
                }
                latencySnapshot.forEach { (key, snap) ->
                    queries.upsertLatency(
                        window_start_epoch_ms = key.windowStartEpochMs,
                        metric_name = key.name,
                        p50_ms = snap.p50Ms,
                        p95_ms = snap.p95Ms,
                        p99_ms = snap.p99Ms,
                        sample_count = snap.sampleCount,
                    )
                }
            }
        }
    }

    private data class WindowedKey(val windowStartEpochMs: Long, val name: String)
}

/**
 * Reservoir sampler (Vitter's Algorithm R) over Long samples. Thread-safe
 * via `synchronized` blocks — observe + snapshotAndReset are the only
 * mutating paths and contention is negligible at our call frequencies.
 */
internal class ReservoirSampler(
    private val capacity: Int,
    private val random: Random,
) {
    private val lock = Any()
    private val reservoir = LongArray(capacity)
    private var count: Long = 0L

    fun observe(sample: Long) = synchronized(lock) {
        count++
        when {
            count <= capacity -> reservoir[(count - 1).toInt()] = sample
            else -> {
                // Vitter's Algorithm R: replace at a random index with
                // probability capacity/count.
                val idx = random.nextLong(count)
                if (idx < capacity) reservoir[idx.toInt()] = sample
            }
        }
    }

    /** Drains the reservoir into a percentile snapshot and resets the count. */
    fun snapshotAndReset(): LatencySnapshot = synchronized(lock) {
        val n = minOf(count, capacity.toLong()).toInt()
        if (n == 0) return@synchronized LatencySnapshot.EMPTY
        val sorted = reservoir.copyOf(n)
        sorted.sort()
        val snap = LatencySnapshot(
            sampleCount = count,
            p50Ms = percentile(sorted, 50),
            p95Ms = percentile(sorted, 95),
            p99Ms = percentile(sorted, 99),
        )
        count = 0L
        snap
    }

    private fun percentile(sortedSamples: LongArray, p: Int): Long {
        val n = sortedSamples.size
        if (n == 0) return 0L
        // Nearest-rank percentile: ceil(p/100 * n) - 1, clamped.
        val rank = ((p * n + 99) / 100 - 1).coerceIn(0, n - 1)
        return sortedSamples[rank]
    }
}

internal data class LatencySnapshot(
    val sampleCount: Long,
    val p50Ms: Long,
    val p95Ms: Long,
    val p99Ms: Long,
) {
    companion object {
        val EMPTY = LatencySnapshot(sampleCount = 0L, p50Ms = 0L, p95Ms = 0L, p99Ms = 0L)
    }
}
