package com.contextsolutions.localagent.app.observability

import android.util.Log
import com.contextsolutions.localagent.app.service.AuxModelLifecycleCoordinator
import com.contextsolutions.localagent.app.service.InferenceSessionManager
import com.contextsolutions.localagent.inference.SessionState
import com.contextsolutions.localagent.app.service.UnloadReason
import com.contextsolutions.localagent.inference.MemoryHeadroomProvider
import com.contextsolutions.localagent.inference.SystemMemoryThresholds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Proactive memory-pressure watchdog (PR #16).
 *
 * The motivating scenario: Gemma is eager-loaded on chat entry and held
 * resident. Some time later the user opens another heavy app (browser,
 * camera, game) which squeezes system memory. The OS doesn't always fire
 * `onTrimMemory` early enough to prevent jank/crashes — by the time
 * `TRIM_MEMORY_RUNNING_CRITICAL` arrives, we're already in the LMK danger
 * zone. This watchdog polls `availMem` on a coroutine while the model is
 * loaded and pre-emptively unloads at a 1 GiB floor, mirroring how
 * [MainThreadHeartbeatWatchdog] pre-empts the system_server watchdog
 * before it can fire.
 *
 * Architecture:
 *  - Subscribes to [InferenceSessionManager.state]. While state is
 *    [SessionState.Loaded], polls every [pollIntervalMs]. On any non-Loaded
 *    state, `collectLatest` cancels the polling block automatically.
 *  - When `availableBytes()` drops below [SystemMemoryThresholds.watchdogUnloadBytes],
 *    calls `forceUnload(UnloadReason.LowMemory)` and stops polling — the next
 *    `Loaded` transition (a subsequent send triggered a fresh load) will
 *    re-arm.
 *  - Does NOT abort mid-generation. `forceUnload()` already defers when
 *    `activeGenerationCount > 0`, matching how `TrimMemory` and
 *    `MainThreadWatchdog` behave. Releasing 5 s late is the right trade —
 *    cutting off mid-stream would be a worse UX than the current scope of
 *    this PR.
 *
 * Lifecycle: `start()` is called from `LocalAgentApplication.onCreate()`,
 * alongside the existing main-thread watchdog setup. The watchdog runs for
 * the lifetime of the process — there's no background/foreground gate here
 * because the pressure can build while we're backgrounded too (and the OS
 * is more likely to reclaim us when we're backgrounded with 3 GB resident).
 */
class MemoryPressureWatchdog(
    private val sessionManager: InferenceSessionManager,
    private val auxModelCoordinator: AuxModelLifecycleCoordinator,
    private val provider: MemoryHeadroomProvider,
    private val thresholds: SystemMemoryThresholds,
) {

    /** Mutable for tests; not part of the public API. */
    internal var pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS

    /** Mutable for tests so a TestDispatcher can drive the polling loop. */
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var supervisor: Job? = null

    /**
     * Idempotent. Safe to call from [com.contextsolutions.localagent.app.LocalAgentApplication.onCreate].
     */
    fun start() {
        if (supervisor != null) return
        supervisor = scope.launch {
            sessionManager.state.collectLatest { current ->
                if (current !is SessionState.Loaded) return@collectLatest
                Log.i(TAG, "watchdog armed: model loaded; polling every ${pollIntervalMs}ms")
                while (isActive) {
                    val avail = provider.availableBytes()
                    val unloadThresholdBytes = thresholds.watchdogUnloadBytes
                    if (avail < unloadThresholdBytes) {
                        val mb = avail / 1024 / 1024
                        val thresholdMb = unloadThresholdBytes / 1024 / 1024
                        Log.w(
                            TAG,
                            "memory pressure: avail=${mb}MB < threshold=${thresholdMb}MB; forcing unload",
                        )
                        sessionManager.forceUnload(UnloadReason.LowMemory)
                        // Aux models are tiny (~92 MB combined) but every
                        // byte helps below the 1 GiB floor. The call is a
                        // no-op when they aren't loaded. Same UnloadReason
                        // for traceability — both surfaces respond to the
                        // same pressure signal.
                        auxModelCoordinator.forceUnload(UnloadReason.LowMemory)
                        // Stop polling — the next Loaded state transition
                        // re-enters collectLatest's block and re-arms.
                        break
                    }
                    delay(pollIntervalMs)
                }
            }
        }
    }

    /**
     * Stop the watchdog. Idempotent. Tests call this in their teardown so
     * the scope's coroutines don't leak across cases.
     */
    fun stop() {
        supervisor?.cancel()
        supervisor = null
    }

    private companion object {
        const val TAG = "MemoryPressureWatchdog"

        /**
         * 5 seconds. Tightened from the initial 15 s after on-device
         * repro showed that another app's allocation pressure can spike
         * past the threshold inside one poll window — fast enough to
         * cause jank/crashes before our reaction. The IPC into
         * `system_server` is microsecond-cost, so the higher rate has
         * negligible battery impact.
         */
        const val DEFAULT_POLL_INTERVAL_MS: Long = 5_000L
    }
}
