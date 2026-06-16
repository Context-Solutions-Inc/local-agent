package com.contextsolutions.localagent.app.spike

import android.os.Debug
import kotlinx.serialization.Serializable

/**
 * The numbers we need from the M0 spike to fill in `docs/M0_DECISION_MEMO.md` and
 * decide whether Gemma 4 E4B holds on Pixel 7 or we drop to E2B.
 *
 * Captured per-prompt; the harness aggregates across the canonical prompt set.
 */
@Serializable
data class SpikePromptResult(
    val promptId: String,
    val prompt: String,
    val coldLoadMs: Long?,                    // populated only on the first prompt of a run
    val firstTokenLatencyMs: Long,
    val totalGenerationMs: Long,
    val totalTokens: Int,
    val sustainedTokensPerSecond: Double,
    val peakRssBytes: Long,
    val peakNativeHeapBytes: Long,
    val thermalStateAtStart: Int,
    val thermalStateAtEnd: Int,
    val thermalStateMaxObserved: Int,
    val cancelled: Boolean = false,
    val errorMessage: String? = null,
)

@Serializable
data class SpikeRun(
    val runId: String,
    val device: String,
    val androidVersion: String,
    val accelerator: String,
    val modelPath: String,
    val kvCacheTokens: Int,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long,
    val results: List<SpikePromptResult>,
) {
    fun summary(): SpikeSummary {
        val firstTokenP50 = results.map { it.firstTokenLatencyMs }.median()
        val firstTokenP95 = results.map { it.firstTokenLatencyMs }.percentile(95)
        val tpsMean = results.map { it.sustainedTokensPerSecond }.average()
        val peakRss = results.maxOfOrNull { it.peakRssBytes } ?: 0L
        return SpikeSummary(
            firstTokenLatencyP50Ms = firstTokenP50,
            firstTokenLatencyP95Ms = firstTokenP95,
            sustainedTokensPerSecondMean = tpsMean,
            peakRssBytes = peakRss,
            promptsRun = results.size,
        )
    }
}

@Serializable
data class SpikeSummary(
    val firstTokenLatencyP50Ms: Long,
    val firstTokenLatencyP95Ms: Long,
    val sustainedTokensPerSecondMean: Double,
    val peakRssBytes: Long,
    val promptsRun: Int,
)

private fun List<Long>.median(): Long = if (isEmpty()) 0 else sorted()[size / 2]

private fun List<Long>.percentile(p: Int): Long {
    if (isEmpty()) return 0
    val sorted = sorted()
    val index = ((p / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
    return sorted[index]
}

/**
 * Snapshot of memory usage for the current process. Pixel 7 has 8GB total RAM with
 * a per-app ceiling of ~4GB before the OS aggressively trims us. Tracking peak RSS
 * across a sustained generation is the single most important data point for the
 * E4B-vs-E2B decision in the M0 memo.
 */
object MemorySnapshot {
    fun rssBytes(): Long {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        // totalPss is in KB; multiply to bytes.
        return info.totalPss.toLong() * 1024L
    }

    fun nativeHeapBytes(): Long = Debug.getNativeHeapAllocatedSize()
}
