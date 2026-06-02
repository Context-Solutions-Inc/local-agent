package com.contextsolutions.mobileagent.app.observability

import android.util.Log
import com.contextsolutions.mobileagent.inference.MemoryHeadroomProvider
import com.contextsolutions.mobileagent.inference.MemoryStatus
import com.contextsolutions.mobileagent.inference.SystemMemoryThresholds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * PR #18 — drives the chat-header system-RAM status dot.
 *
 * Sibling of [MemoryPressureWatchdog]: same `MemoryHeadroomProvider` + same
 * thresholds, but polls unconditionally (not gated on `SessionState.Loaded`)
 * because the indicator should reflect device state regardless of whether
 * the model is currently resident. No side effects — pure read + publish.
 *
 * Started from [com.contextsolutions.mobileagent.app.MobileAgentApplication.onCreate].
 */
class SystemMemoryMonitor(
    private val provider: MemoryHeadroomProvider,
    private val thresholds: SystemMemoryThresholds,
) {

    /** Mutable for tests; not part of the public API. */
    internal var pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS

    /** Mutable for tests so a TestDispatcher can drive the polling loop. */
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var supervisor: Job? = null

    private val _status = MutableStateFlow(thresholds.classify(provider.availableBytes()))

    /** Hot StateFlow: latest classification of free system RAM. */
    val status: StateFlow<MemoryStatus> = _status.asStateFlow()

    /** Idempotent. Safe to call from `MobileAgentApplication.onCreate`. */
    fun start() {
        if (supervisor != null) return
        val initialAvail = provider.availableBytes()
        val initialStatus = thresholds.classify(initialAvail)
        _status.value = initialStatus
        Log.i(
            TAG,
            "monitor armed: status=$initialStatus avail=${initialAvail.mb()}MB " +
                "thresholds=watchdog<${thresholds.watchdogUnloadBytes.mb()}MB " +
                "yellow<${thresholds.coldLoadMinBytes.mb()}MB green>=${thresholds.coldLoadMinBytes.mb()}MB " +
                "(poll=${pollIntervalMs}ms)",
        )
        supervisor = scope.launch {
            while (isActive) {
                val avail = provider.availableBytes()
                val nextStatus = thresholds.classify(avail)
                val previous = _status.value
                if (nextStatus != previous) {
                    Log.i(
                        TAG,
                        "status transition: $previous -> $nextStatus (avail=${avail.mb()}MB)",
                    )
                }
                _status.value = nextStatus
                delay(pollIntervalMs)
            }
        }
    }

    /** Stop the monitor. Idempotent — tests call it in teardown. */
    fun stop() {
        supervisor?.cancel()
        supervisor = null
    }

    private companion object {
        const val TAG = "SystemMemoryMonitor"

        /**
         * 5 seconds — matches [MemoryPressureWatchdog.DEFAULT_POLL_INTERVAL_MS]
         * so the indicator and the watchdog evaluate the same poll cadence.
         */
        const val DEFAULT_POLL_INTERVAL_MS: Long = 5_000L

        private fun Long.mb(): Long = this / 1024 / 1024
    }
}
