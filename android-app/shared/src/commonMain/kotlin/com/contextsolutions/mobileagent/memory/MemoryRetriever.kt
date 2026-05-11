package com.contextsolutions.mobileagent.memory

import com.contextsolutions.mobileagent.telemetry.CounterNames
import com.contextsolutions.mobileagent.telemetry.LatencyNames
import com.contextsolutions.mobileagent.telemetry.NoOpTelemetryCounters
import com.contextsolutions.mobileagent.telemetry.TelemetryCounters

/**
 * Retrieval entry point for the agent loop. Composes [EmbedderEngine] +
 * [MemoryStore] into the single call the [com.contextsolutions.mobileagent.agent.AgentLoop]
 * makes per user turn.
 *
 * **Order of operations.** Embed the user query, brute-force cosine over
 * non-expired memories, return up to [DEFAULT_TOP_K] hits above
 * [DEFAULT_THRESHOLD] sorted by similarity descending. The store atomically
 * bumps `last_accessed` and `access_count` on every hit (Phase B contract).
 *
 * **Failure mode (PRD §3.2.4).** Memory is an enhancement layer — every
 * failure path returns an empty list:
 *  - Embedder unavailable / throws → empty
 *  - Store throws → empty
 *  - Empty query → empty (skip entirely)
 *
 * The first time we hit one of these we log via [logger]; subsequent
 * failures are silent (`oncePerLifetime` keeps logcat from spamming).
 *
 * **Threading.** Both engine and store dispatch their own work onto IO;
 * this class adds nothing.
 */
class MemoryRetriever(
    private val embedder: EmbedderEngine,
    private val store: MemoryStore,
    private val nowProvider: () -> Long,
    private val logger: (String) -> Unit = {},
    private val counters: TelemetryCounters = NoOpTelemetryCounters,
) {

    private var failureLogged = false

    /**
     * @param query the user message — embedded directly (no pre-processing).
     * @param k upper bound on returned hits (PRD §3.2.4 default 5).
     * @param threshold minimum cosine similarity (PRD §3.2.4 default 0.5).
     */
    suspend fun retrieve(
        query: String,
        k: Int = DEFAULT_TOP_K,
        threshold: Double = DEFAULT_THRESHOLD,
    ): List<MemoryHit> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val now = nowProvider()
        val startMs = nowProvider()
        return try {
            val output = embedder.embed(trimmed)
                ?: return emptyList<MemoryHit>().also { logFailureOnce("embedder unavailable") }
            val hits = store.retrieveTopK(
                queryEmbedding = output.vector,
                k = k,
                threshold = threshold,
                now = now,
            )
            // M6 Phase C — `memory_retrieved_total` counts non-empty
            // retrievals only (matching the M5 handoff contract). Latency
            // is always recorded so we can spot regression even when no
            // memories matched.
            counters.observeLatency(LatencyNames.MEMORY_RETRIEVAL_MS, nowProvider() - startMs)
            if (hits.isNotEmpty()) counters.increment(CounterNames.MEMORY_RETRIEVED_TOTAL)
            hits
        } catch (t: Throwable) {
            logFailureOnce("memory retrieval failed: ${t.message}")
            emptyList()
        }
    }

    private fun logFailureOnce(message: String) {
        if (failureLogged) return
        failureLogged = true
        logger("[memory] $message — memory subsystem will return empty for this session")
    }

    companion object {
        const val DEFAULT_TOP_K: Int = MemoryStore.DEFAULT_TOP_K
        const val DEFAULT_THRESHOLD: Double = MemoryStore.DEFAULT_RETRIEVAL_THRESHOLD
    }
}
