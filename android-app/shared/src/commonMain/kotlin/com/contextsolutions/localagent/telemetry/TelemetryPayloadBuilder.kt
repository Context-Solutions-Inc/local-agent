package com.contextsolutions.localagent.telemetry

import com.contextsolutions.localagent.db.TelemetryAggregateQueries

/**
 * Reads the two telemetry aggregate tables and produces the themed Firebase
 * Analytics event payloads listed in M6_PLAN §3.3.2.
 *
 * **Counter-only contract (PRD §4.4 + WS-12).** This builder reads ONLY
 * [TelemetryAggregateQueries]. It MUST NEVER read the `memories` or
 * `messages` tables — those carry user content. The `TelemetryAggregate.sq`
 * header documents the exclusion contract; `Memories.sq` carries a
 * matching block. M6 Phase C unit tests assert that an instance constructed
 * from a populated database emits payloads with no marker strings
 * borrowed from memory text.
 *
 * Events are partitioned by stable prefix:
 *
 *  - `preflight_*`, `inference_*`, `first_token_*` → `daily_inference`
 *    + `daily_preflight` (split by counter source).
 *  - `search_*` → `daily_search`.
 *  - `memory_*` → `daily_memory`.
 *
 * Each event fits well under Firebase Analytics' 25-parameter cap
 * (the partition was chosen with that headroom in mind).
 *
 * Output bundles are keyed on the rolled-up UTC-day window so a single
 * upload can carry multiple days' worth of pending data (e.g., the user
 * was offline for 3 days then came back online).
 */
class TelemetryPayloadBuilder(
    private val queries: TelemetryAggregateQueries,
) {

    /**
     * Returns all events that should be transmitted for windows strictly
     * before [windowCutoffEpochMs]. The caller (uploader) typically passes
     * the start-of-current-day so still-open windows are not transmitted
     * mid-flight.
     */
    fun build(windowCutoffEpochMs: Long): Output {
        val counters = queries
            .selectUnuploadedCountersBefore(window_start_epoch_ms = windowCutoffEpochMs)
            .executeAsList()
        val latencies = queries
            .selectUnuploadedLatenciesBefore(window_start_epoch_ms = windowCutoffEpochMs)
            .executeAsList()

        if (counters.isEmpty() && latencies.isEmpty()) return Output.EMPTY

        // Group both counters + latencies by window. Each window produces up
        // to 4 events (`daily_inference`, `daily_preflight`, `daily_search`,
        // `daily_memory`) — only emit an event when at least one parameter
        // landed in that bucket.
        val byWindow = sortedMapOf<Long, EventBuilderForWindow>()
        for (row in counters) {
            byWindow.getOrPut(row.window_start_epoch_ms) { EventBuilderForWindow(row.window_start_epoch_ms) }
                .addCounter(row.counter_name, row.counter_value)
        }
        for (row in latencies) {
            byWindow.getOrPut(row.window_start_epoch_ms) { EventBuilderForWindow(row.window_start_epoch_ms) }
                .addLatency(row.metric_name, row.p50_ms, row.p95_ms, row.p99_ms, row.sample_count)
        }

        val events = mutableListOf<AnalyticsSink.AnalyticsEvent>()
        val markers = mutableListOf<Marker>()
        for ((_, b) in byWindow) {
            for (event in b.toEvents()) {
                events += event
                markers += b.markers(event.name)
            }
        }
        return Output(events = events, markers = markers)
    }

    /**
     * What an upload batch looks like from the builder's perspective.
     * [events] is the list of Firebase events to dispatch via
     * [AnalyticsSink.send]; [markers] is the list of `(window, name, table)`
     * tuples the uploader must run [markCounterUploaded] / [markLatencyUploaded]
     * against once dispatch succeeds.
     */
    data class Output(
        val events: List<AnalyticsSink.AnalyticsEvent>,
        val markers: List<Marker>,
    ) {
        companion object {
            val EMPTY = Output(events = emptyList(), markers = emptyList())
        }
    }

    /** Tuple identifying a single row in either aggregate table. */
    data class Marker(
        val windowStartEpochMs: Long,
        val name: String,
        val source: Source,
    ) {
        enum class Source { Counter, Latency }
    }

    // -- Internal grouping -----------------------------------------------------

    private class EventBuilderForWindow(val windowStartEpochMs: Long) {
        private val daily_inference = mutableMapOf<String, Long>()
        private val daily_preflight = mutableMapOf<String, Long>()
        private val daily_search = mutableMapOf<String, Long>()
        private val daily_memory = mutableMapOf<String, Long>()

        /** Counter-row markers grouped by destination event name. */
        private val counterMarkers = mutableListOf<Pair<String, String>>() // (eventName, counterName)
        private val latencyMarkers = mutableListOf<Pair<String, String>>()

        fun addCounter(name: String, value: Long) {
            val target = bucketForName(name)
            target.merge(name, value) { a, b -> a + b }
            counterMarkers += eventNameForBucket(target) to name
        }

        fun addLatency(metric: String, p50: Long, p95: Long, p99: Long, sampleCount: Long) {
            val target = bucketForLatency(metric)
            target["${metric}_p50_ms"] = p50
            target["${metric}_p95_ms"] = p95
            target["${metric}_p99_ms"] = p99
            target["${metric}_sample_count"] = sampleCount
            latencyMarkers += eventNameForBucket(target) to metric
        }

        fun toEvents(): List<AnalyticsSink.AnalyticsEvent> {
            val out = mutableListOf<AnalyticsSink.AnalyticsEvent>()
            if (daily_inference.isNotEmpty()) {
                out += event(EVENT_DAILY_INFERENCE, daily_inference)
            }
            if (daily_preflight.isNotEmpty()) {
                out += event(EVENT_DAILY_PREFLIGHT, daily_preflight)
            }
            if (daily_search.isNotEmpty()) {
                out += event(EVENT_DAILY_SEARCH, daily_search)
            }
            if (daily_memory.isNotEmpty()) {
                out += event(EVENT_DAILY_MEMORY, daily_memory)
            }
            return out
        }

        fun markers(eventName: String): List<Marker> {
            val out = mutableListOf<Marker>()
            counterMarkers.filter { it.first == eventName }.forEach {
                out += Marker(windowStartEpochMs, it.second, Marker.Source.Counter)
            }
            latencyMarkers.filter { it.first == eventName }.forEach {
                out += Marker(windowStartEpochMs, it.second, Marker.Source.Latency)
            }
            return out
        }

        private fun event(name: String, params: Map<String, Long>): AnalyticsSink.AnalyticsEvent =
            AnalyticsSink.AnalyticsEvent(
                name = name,
                params = params.toMap() + mapOf("window_start_epoch_ms" to windowStartEpochMs),
            )

        private fun bucketForName(name: String): MutableMap<String, Long> = when {
            name.startsWith("preflight_") -> daily_preflight
            name.startsWith("classifier_") -> daily_preflight
            name.startsWith("inference_") -> daily_inference
            name.startsWith("search_") -> daily_search
            name.startsWith("memory_") -> daily_memory
            name.startsWith("embedder_") -> daily_memory
            // Unknown counter — route into daily_inference as a safety net
            // (the alternative is dropping it, which would silently lose
            // data the v1.x might add).
            else -> daily_inference
        }

        private fun bucketForLatency(metric: String): MutableMap<String, Long> = when {
            metric == LatencyNames.PREFLIGHT_MS -> daily_preflight
            metric == LatencyNames.FIRST_TOKEN_MS -> daily_inference
            metric == LatencyNames.SEARCH_MS -> daily_search
            metric == LatencyNames.MEMORY_RETRIEVAL_MS -> daily_memory
            else -> daily_inference
        }

        private fun eventNameForBucket(bucket: MutableMap<String, Long>): String = when (bucket) {
            daily_inference -> EVENT_DAILY_INFERENCE
            daily_preflight -> EVENT_DAILY_PREFLIGHT
            daily_search -> EVENT_DAILY_SEARCH
            daily_memory -> EVENT_DAILY_MEMORY
            else -> EVENT_DAILY_INFERENCE
        }
    }

    companion object {
        const val EVENT_DAILY_INFERENCE = "daily_inference"
        const val EVENT_DAILY_PREFLIGHT = "daily_preflight"
        const val EVENT_DAILY_SEARCH = "daily_search"
        const val EVENT_DAILY_MEMORY = "daily_memory"
    }
}
