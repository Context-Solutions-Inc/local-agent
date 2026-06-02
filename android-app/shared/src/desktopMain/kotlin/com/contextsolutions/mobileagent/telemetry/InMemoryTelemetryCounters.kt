package com.contextsolutions.mobileagent.telemetry

import com.contextsolutions.mobileagent.db.TelemetryAggregateQueries
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Desktop [TelemetryCounters] + [TelemetryFlusher] (docs/DESKTOP_PORT_PLAN.md,
 * Phase 7). A verbatim copy of the Android `InMemoryTelemetryCounters` — the
 * class is pure JVM (ConcurrentHashMap / AtomicLong / Dispatchers.IO) and depends
 * only on the shared [TelemetryAggregateQueries], so it's duplicated into
 * `desktopMain` (the same "separate actual so desktop need not depend on
 * androidMain" precedent as `Clock.desktop.kt`) rather than shared through a JVM
 * source set. Keep the two in sync if the recording/flush logic changes.
 *
 * Recording (hot path): each [increment] / [observeLatency] is a single
 * ConcurrentHashMap lookup plus an atomic add (counters) or a synchronized
 * reservoir update (latencies). Persistence: [flush] snapshots the in-memory
 * map under [flushMutex], upserts on [ioDispatcher], then resets. Counters are
 * bucketed by the UTC-day window at RECORD time.
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

    override suspend fun flush(): Unit = flushMutex.withLock {
        val counterSnapshot = HashMap<WindowedKey, Long>(counters.size)
        val latencySnapshot = HashMap<WindowedKey, LatencySnapshot>(latencies.size)

        val counterIterator = counters.entries.iterator()
        while (counterIterator.hasNext()) {
            val entry = counterIterator.next()
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
 * Reservoir sampler (Vitter's Algorithm R) over Long samples. Thread-safe via
 * `synchronized` blocks. Copy of the Android impl (see this file's class doc).
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
                val idx = random.nextLong(count)
                if (idx < capacity) reservoir[idx.toInt()] = sample
            }
        }
    }

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
