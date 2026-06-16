package com.contextsolutions.localagent.app.service

import com.contextsolutions.localagent.inference.ThermalStatus
import com.contextsolutions.localagent.inference.ThermalStatusProvider
import com.contextsolutions.localagent.telemetry.NoOpTelemetryCounters
import com.contextsolutions.localagent.telemetry.TelemetryCounters
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Generic lifecycle coordinator for an auxiliary on-device model — the
 * classifier (DistilBERT INT8, ~68 MB) or the embedder (MiniLM INT8,
 * ~24 MB). Mirrors [InferenceSessionManager]'s state machine so the two
 * 91 MB worth of native heap follows the same load/unload triggers Gemma
 * does:
 *  - Lazy load on first use (via [withSession]).
 *  - 5-minute rolling idle unload after the last call ends.
 *  - Eager [warmUpIfPossible] called from the chat-screen RESUME hook,
 *    thermal-gated identically to Gemma.
 *  - [forceUnload] for `onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL+)` and
 *    [MainThreadHeartbeatWatchdog] trips. Defers if a call is in flight
 *    (classify/embed complete in ~40–113 ms p95 so the defer window is
 *    short).
 *
 * Why a separate type from [InferenceSessionManager]: Gemma exposes a
 * three-state public `SessionState` to UI, runs through a foreground
 * service, and re-routes around a separate `Conversation` per turn.
 * The aux models have none of that — just load/use/unload — so they get
 * a thinner state machine. The triggers and timeouts are identical.
 *
 * The underlying engine ([com.contextsolutions.localagent.classifier.LiteRtClassifierEngine] /
 * [com.contextsolutions.localagent.memory.LiteRtEmbedderEngine]) already
 * serialises `warmUp` / `classify`/`embed` / `unload` via its own
 * internal `Mutex`. This session adds an OUTER mutex protecting its own
 * bookkeeping (active count, idle job, pending unload). Two mutexes,
 * disjoint responsibilities — same pattern Gemma uses.
 */
internal class AuxModelSession(
    private val name: String,
    private val warmUpDelegate: suspend () -> Boolean,
    private val unloadDelegate: suspend () -> Unit,
    private val thermalStatusProvider: ThermalStatusProvider,
    private val counters: TelemetryCounters = NoOpTelemetryCounters,
    private val counterNames: CounterNames,
) {

    /** Mutable for tests. */
    internal var idleTimeout: Duration = DEFAULT_IDLE_TIMEOUT
    internal var idleTimeoutAfterWarmUp: Duration = DEFAULT_IDLE_TIMEOUT_AFTER_WARMUP
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val mutex = Mutex()
    private var loaded: Boolean = false
    private var idleUnloadJob: Job? = null
    private var activeUseCount: Int = 0
    private var pendingForceUnload: Boolean = false

    val isLoaded: Boolean get() = loaded

    /**
     * RAII entry for one inference call. Loads the engine if not loaded,
     * cancels any pending idle unload, runs [block] (the underlying
     * `classify` / `embed` call), and on completion either schedules a
     * fresh idle unload or — if a [forceUnload] queued up while [block]
     * was in flight — unloads immediately.
     *
     * If the engine fails to load, [block] still runs. The underlying
     * delegate has a `model ?: return null` guard so its `classify` /
     * `embed` returns null on a missing model. Returning null is the
     * documented graceful-degradation path (PRD §3.2.1 / §3.2.4).
     */
    suspend fun <R> withSession(block: suspend () -> R): R {
        val acquired = mutex.withLock {
            val ok = ensureLoadedLocked()
            if (ok) {
                activeUseCount++
                cancelIdleUnloadLocked()
            }
            ok
        }
        return try {
            block()
        } finally {
            if (acquired) {
                withContext(NonCancellable) {
                    mutex.withLock { onUseEndedLocked() }
                }
            }
        }
    }

    /**
     * Eager warm-up entry called from `MainViewModel.warmUpAuxEngines` on
     * Chat-screen RESUME:
     *  - Already loaded → [AuxWarmUpOutcome.AlreadyLoaded].
     *  - Thermal ≥ SEVERE → [AuxWarmUpOutcome.SkippedThermal].
     *  - Otherwise → load under the mutex, schedule a 60 s post-warm-up
     *    idle if no use follows, return [AuxWarmUpOutcome.Loaded] or
     *    [AuxWarmUpOutcome.Failed].
     *
     * Never throws — failures are reported via [AuxWarmUpOutcome.Failed]
     * so the chat-screen RESUME hook doesn't blow up the UI.
     */
    suspend fun warmUpIfPossible(): AuxWarmUpOutcome {
        if (loaded) {
            counters.increment(counterNames.warmupAlreadyLoadedTotal)
            return AuxWarmUpOutcome.AlreadyLoaded
        }
        val thermal = thermalStatusProvider.current()
        if (thermal.isThrottling) {
            counters.increment(counterNames.warmupSkippedThermalTotal)
            return AuxWarmUpOutcome.SkippedThermal(thermal)
        }
        return try {
            mutex.withLock {
                if (loaded) {
                    counters.increment(counterNames.warmupAlreadyLoadedTotal)
                    AuxWarmUpOutcome.AlreadyLoaded
                } else {
                    val ok = ensureLoadedLocked()
                    if (ok) {
                        // Bound how long a warmed-up-but-unused engine
                        // stays resident. The post-warmup window only
                        // fires when the user warmed up the engine and
                        // walked away.
                        if (activeUseCount == 0) {
                            scheduleIdleUnloadLocked(idleTimeoutAfterWarmUp)
                        }
                        counters.increment(counterNames.warmupLoadedTotal)
                        AuxWarmUpOutcome.Loaded
                    } else {
                        counters.increment(counterNames.warmupFailedTotal)
                        AuxWarmUpOutcome.Failed(IllegalStateException("$name warmUp returned failure"))
                    }
                }
            }
        } catch (t: Throwable) {
            counters.increment(counterNames.warmupFailedTotal)
            AuxWarmUpOutcome.Failed(t)
        }
    }

    /**
     * Free the engine immediately. Mirrors
     * [InferenceSessionManager.forceUnload]: defers the actual unload if
     * a call is in flight, else unloads immediately.
     *
     * Reason-tagged counter increments happen eagerly so the telemetry
     * signal lands even if the actual unload throws.
     */
    fun forceUnload(reason: UnloadReason = UnloadReason.Manual) {
        when (reason) {
            UnloadReason.TrimMemory ->
                counters.increment(counterNames.unloadedTrimMemoryTotal)
            UnloadReason.MainThreadWatchdog ->
                counters.increment(counterNames.unloadedWatchdogTotal)
            // PR #16 — aux models don't have a dedicated low-memory counter
            // (they're 92 MB combined; the Gemma signal dominates). Roll into
            // the trim-memory counter for telemetry — both are "OS or our
            // proactive watcher said free RAM now."
            UnloadReason.LowMemory ->
                counters.increment(counterNames.unloadedTrimMemoryTotal)
            UnloadReason.Manual -> Unit
        }
        scope.launch {
            mutex.withLock {
                if (activeUseCount > 0) {
                    pendingForceUnload = true
                } else {
                    cancelIdleUnloadLocked()
                    unloadLocked()
                }
            }
        }
    }

    private suspend fun ensureLoadedLocked(): Boolean {
        cancelIdleUnloadLocked()
        if (loaded) return true
        val ok = try {
            withContext(ioDispatcher) { warmUpDelegate() }
        } catch (_: Throwable) {
            false
        }
        loaded = ok
        return ok
    }

    private suspend fun onUseEndedLocked() {
        activeUseCount = (activeUseCount - 1).coerceAtLeast(0)
        if (activeUseCount > 0) return
        if (pendingForceUnload) {
            pendingForceUnload = false
            unloadLocked()
        } else {
            scheduleIdleUnloadLocked()
        }
    }

    private fun cancelIdleUnloadLocked() {
        idleUnloadJob?.cancel()
        idleUnloadJob = null
    }

    private fun scheduleIdleUnloadLocked(timeout: Duration = idleTimeout) {
        cancelIdleUnloadLocked()
        idleUnloadJob = scope.launch {
            delay(timeout)
            mutex.withLock {
                if (activeUseCount == 0 && loaded) {
                    unloadLocked()
                    counters.increment(counterNames.unloadedIdleTotal)
                }
            }
        }
    }

    private suspend fun unloadLocked() {
        if (!loaded) return
        withContext(ioDispatcher) {
            runCatching { unloadDelegate() }
        }
        loaded = false
    }

    /**
     * Per-engine telemetry counter names. The session is generic over the
     * engine but the counters that surface in dashboards need to
     * distinguish classifier from embedder. Defined as a value class so
     * call sites don't have to remember the seven separate names.
     */
    internal data class CounterNames(
        val warmupLoadedTotal: String,
        val warmupAlreadyLoadedTotal: String,
        val warmupSkippedThermalTotal: String,
        val warmupFailedTotal: String,
        val unloadedIdleTotal: String,
        val unloadedTrimMemoryTotal: String,
        val unloadedWatchdogTotal: String,
    )

    companion object {
        /** Idle timeout after the last classify/embed before unloading. */
        val DEFAULT_IDLE_TIMEOUT: Duration = 5.minutes
        /** Shorter idle window applied after a warm-up that didn't lead to a use. */
        val DEFAULT_IDLE_TIMEOUT_AFTER_WARMUP: Duration = 60.seconds
    }
}

/**
 * Outcome of [AuxModelSession.warmUpIfPossible]. No `AlreadyLoading`
 * state because the aux engines load in ~100–500 ms; the simpler
 * mutex-wait suffices and the additional state isn't worth its
 * complexity at that timescale.
 */
internal sealed interface AuxWarmUpOutcome {
    data object AlreadyLoaded : AuxWarmUpOutcome
    data class SkippedThermal(val status: ThermalStatus) : AuxWarmUpOutcome
    data object Loaded : AuxWarmUpOutcome
    data class Failed(val cause: Throwable) : AuxWarmUpOutcome
}
