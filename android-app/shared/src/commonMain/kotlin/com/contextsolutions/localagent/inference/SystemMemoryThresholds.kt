package com.contextsolutions.localagent.inference

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Single source of truth for the system-RAM thresholds used across the
 * inference + chat surfaces. Consolidates four previously-inlined `const val`
 * declarations:
 *
 *  - `MemoryPressureWatchdog.DEFAULT_UNLOAD_THRESHOLD_BYTES` (800 MB)
 *  - `ChatViewModel.MIN_BYTES_FOR_HOT_PATH` (1 GiB)
 *  - `ChatViewModel.MIN_BYTES_FOR_FRESH_LOAD` (2 GiB)
 *  - `InferenceSessionManager.EAGER_WARMUP_MIN_FREE_BYTES` (2 GiB)
 *
 * Shipped as `:androidApp/src/main/assets/system_memory_config.json`, parsed
 * at app start by `MemoryModule.provideSystemMemoryThresholds`. Mirrors the
 * `MemoryConfig` (memory-extraction tunables) and `PreflightConfig` (router
 * tunables) shape so post-launch telemetry-driven tuning can ship via app
 * update without code change.
 *
 * The `classify` helper drives the chat-header status indicator with the
 * same threshold values the watchdog and send-time gates use, so the dot
 * the user sees can never drift out of sync with the actual gating logic.
 */
@Serializable
data class SystemMemoryThresholds(
    @SerialName("watchdog_unload_bytes")
    val watchdogUnloadBytes: Long,

    @SerialName("hot_path_min_bytes")
    val hotPathMinBytes: Long,

    @SerialName("cold_load_min_bytes")
    val coldLoadMinBytes: Long,
) {
    init {
        require(watchdogUnloadBytes > 0) {
            "watchdogUnloadBytes must be positive, was $watchdogUnloadBytes"
        }
        require(hotPathMinBytes >= watchdogUnloadBytes) {
            "hotPathMinBytes ($hotPathMinBytes) must be >= watchdogUnloadBytes ($watchdogUnloadBytes)"
        }
        require(coldLoadMinBytes >= hotPathMinBytes) {
            "coldLoadMinBytes ($coldLoadMinBytes) must be >= hotPathMinBytes ($hotPathMinBytes)"
        }
    }

    fun classify(availableBytes: Long): MemoryStatus = when {
        availableBytes >= coldLoadMinBytes -> MemoryStatus.Green
        availableBytes >= watchdogUnloadBytes -> MemoryStatus.Yellow
        else -> MemoryStatus.Red
    }

    companion object {
        val DEFAULT: SystemMemoryThresholds = SystemMemoryThresholds(
            watchdogUnloadBytes = 800L * 1024 * 1024,
            hotPathMinBytes = 1L * 1024 * 1024 * 1024,
            coldLoadMinBytes = 2L * 1024 * 1024 * 1024,
        )
    }
}

/**
 * Three-band classification of free system RAM:
 *  - [Green]: at or above the cold-load floor — every gate passes.
 *  - [Yellow]: between the watchdog floor and the cold-load floor —
 *    the model can stay resident, but a fresh cold load is gated.
 *  - [Red]: below the watchdog floor — the watchdog will unload on
 *    its next poll, and send-time gates refuse.
 */
enum class MemoryStatus { Green, Yellow, Red }
