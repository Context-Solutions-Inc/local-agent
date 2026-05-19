package com.contextsolutions.mobileagent.app.service

import com.contextsolutions.mobileagent.inference.Accelerator
import com.contextsolutions.mobileagent.inference.GenerationEvent
import com.contextsolutions.mobileagent.inference.GenerationRequest
import com.contextsolutions.mobileagent.inference.InferenceConfig
import com.contextsolutions.mobileagent.inference.InferenceEngine
import com.contextsolutions.mobileagent.inference.ModelHandle
import com.contextsolutions.mobileagent.telemetry.CounterNames
import com.contextsolutions.mobileagent.telemetry.NoOpTelemetryCounters
import com.contextsolutions.mobileagent.telemetry.TelemetryCounters
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the lifetime of the loaded Gemma 4 model on Android.
 *
 * Responsibilities (PHASE1_PLAN §5 M1 WS-1, M0_DECISION_MEMO Decisions 3 + 5):
 *  - Lazy load on first generation; held resident across rapid turns.
 *  - 5-minute idle unload (PRD §4.2). Scheduled via [scheduleIdleUnloadLocked]
 *    after the last generation ends; cancelled on the next acquire/generate.
 *  - Foreground service start/stop wrapping every active generation, so the OS
 *    keeps the process alive while the user waits for tokens (Android 14+
 *    `specialUse` foregroundServiceType).
 *  - [forceUnload] for `onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL+)` and any other
 *    "free memory NOW" caller. If a generation is in flight, the unload is
 *    deferred to the moment that generation ends (we cannot unload mid-generate
 *    without crashing the JNI side).
 *
 * Threading: every native call to [InferenceEngine] (load, unload, collect from
 * `generate`'s Flow) runs on [ioDispatcher]. State transitions are serialized
 * through [mutex] so concurrent acquires don't double-load and forceUnload
 * never races with a starting generation.
 *
 * Single-model assumption: v1 ships exactly one Gemma artifact. If [acquire] is
 * called with a different `modelPath` than the currently-loaded one, the existing
 * handle is unloaded and the new path is loaded — but in practice WS-1 only ever
 * passes the one production path.
 */
@Singleton
class InferenceSessionManager @Inject constructor(
    private val engine: InferenceEngine,
    private val foregroundServiceController: ForegroundServiceController,
    private val counters: TelemetryCounters = NoOpTelemetryCounters,
) {

    /**
     * Idle timeout used after the most recent generation ends. Mutable for tests
     * (TestDispatcher / shorter timeout). Not part of the public API.
     */
    internal var idleTimeout: Duration = DEFAULT_IDLE_TIMEOUT

    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val mutex = Mutex()
    private var handle: ModelHandle? = null
    private var idleUnloadJob: Job? = null
    private var activeGenerationCount = 0
    private var pendingForceUnload = false

    private val _state = MutableStateFlow<SessionState>(SessionState.Unloaded)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /**
     * Loads the model if not already loaded with the same `modelPath`. Cancels any
     * pending idle unload and returns the handle. Suspends concurrent acquires —
     * cold load is 4–8 s on Pixel 7 and we never want two engines in memory.
     */
    suspend fun acquire(
        modelPath: String,
        config: InferenceConfig = InferenceConfig(),
    ): ModelHandle = mutex.withLock {
        ensureLoadedLocked(modelPath, config)
    }

    /**
     * Streams a generation, taking care of load + foreground-service + idle-unload
     * scheduling around it. The Flow respects coroutine cancellation: cancelling
     * the collector stops generation mid-stream and the finally block runs idle
     * bookkeeping under [NonCancellable] so we never leak the FGS or the active
     * count after cancellation.
     */
    fun generate(
        modelPath: String,
        request: GenerationRequest,
        config: InferenceConfig = InferenceConfig(),
        toolDispatcher: com.contextsolutions.mobileagent.inference.ToolDispatcher? = null,
    ): Flow<GenerationEvent> = flow {
        val acquired = mutex.withLock {
            val h = ensureLoadedLocked(modelPath, config)
            activeGenerationCount++
            // Cancel any pending unload up-front: we're about to use the model.
            cancelIdleUnloadLocked()
            // Start FGS only on the first concurrent generation; subsequent
            // generations are already covered.
            if (activeGenerationCount == 1) {
                foregroundServiceController.start()
            }
            h
        }
        try {
            engine.generate(acquired, request, toolDispatcher).collect { event -> emit(event) }
        } finally {
            // NonCancellable so cancellation of the collector still runs cleanup.
            withContext(NonCancellable) {
                mutex.withLock { onGenerationEndedLocked() }
            }
        }
    }

    /**
     * Best-effort: free the model immediately. Called from
     * `Application.onTrimMemory` at TRIM_MEMORY_RUNNING_CRITICAL+ and as the
     * caller-driven "free memory" lever.
     *
     * If a generation is in flight, we cannot tear down the engine without
     * crashing the JNI layer. We set [pendingForceUnload] and the unload happens
     * the moment the active generations drain. The chat layer (WS-3/WS-11) will
     * eventually subscribe to memory-pressure events and proactively cancel the
     * in-flight generation; for now, deferring is the safe behaviour.
     */
    fun forceUnload(reason: UnloadReason = UnloadReason.Manual) {
        when (reason) {
            UnloadReason.TrimMemory ->
                counters.increment(CounterNames.INFERENCE_UNLOADED_TRIM_MEMORY_TOTAL)
            UnloadReason.MainThreadWatchdog ->
                counters.increment(CounterNames.INFERENCE_UNLOADED_WATCHDOG_TOTAL)
            UnloadReason.LowMemory ->
                counters.increment(CounterNames.INFERENCE_UNLOADED_LOW_MEMORY_TOTAL)
            UnloadReason.Manual -> Unit
        }
        scope.launch {
            mutex.withLock {
                if (activeGenerationCount > 0) {
                    pendingForceUnload = true
                } else {
                    cancelIdleUnloadLocked()
                    unloadLocked()
                }
            }
        }
    }

    private suspend fun ensureLoadedLocked(
        modelPath: String,
        config: InferenceConfig,
    ): ModelHandle {
        cancelIdleUnloadLocked()
        handle?.let { current ->
            if (current.modelId == modelPath) return current
            // Different artifact requested — unload the old one before loading
            // the new one. Single-artifact v1 should never hit this path, but it
            // keeps the manager robust if/when we add a model picker.
            unloadOnDispatcher(current)
            handle = null
        }
        _state.value = SessionState.Loading
        return try {
            val loaded = withContext(ioDispatcher) { engine.loadModel(modelPath, config) }
            handle = loaded
            _state.value = SessionState.Loaded(loaded.activeAccelerator)
            loaded
        } catch (t: Throwable) {
            _state.value = SessionState.Failed(t.message ?: "Model load failed", t)
            throw t
        }
    }

    private suspend fun onGenerationEndedLocked() {
        activeGenerationCount = (activeGenerationCount - 1).coerceAtLeast(0)
        if (activeGenerationCount > 0) return
        foregroundServiceController.stop()
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

    private fun scheduleIdleUnloadLocked() {
        cancelIdleUnloadLocked()
        idleUnloadJob = scope.launch {
            delay(idleTimeout)
            mutex.withLock {
                // Re-check: a new generation may have started while we waited.
                if (activeGenerationCount == 0) {
                    val wasLoaded = handle != null
                    unloadLocked()
                    if (wasLoaded) counters.increment(CounterNames.INFERENCE_UNLOADED_IDLE_TOTAL)
                }
            }
        }
    }

    private suspend fun unloadLocked() {
        val h = handle ?: return
        unloadOnDispatcher(h)
        handle = null
        _state.value = SessionState.Unloaded
    }

    private suspend fun unloadOnDispatcher(h: ModelHandle) {
        // engine.unload() is synchronous but blocks on a native call; always run
        // off the calling thread per LiteRtInferenceEngine.unload's contract.
        withContext(ioDispatcher) { engine.unload(h) }
    }

    companion object {
        val DEFAULT_IDLE_TIMEOUT: Duration = 5.minutes
    }
}

/**
 * Why an explicit unload was requested. Phase C uses this to disambiguate
 * the manual-debug-button path from the system-memory-pressure path so the
 * `inference_unloaded_trim_memory_total` counter doesn't fire on every
 * developer flip of the "Unload" button.
 */
enum class UnloadReason {
    /** Manual user-driven unload — chat screen debug button. Not counted. */
    Manual,

    /** `Application.onTrimMemory()` at TRIM_MEMORY_RUNNING_CRITICAL+. */
    TrimMemory,

    /**
     * Fired by [com.contextsolutions.mobileagent.observability.MainThreadHeartbeatWatchdog]
     * when the main thread fails to ack heartbeats for >20 s. Pre-emptive
     * release before `system_server`'s own ~60 s watchdog times out and
     * soft-reboots the device (root cause analysis in the M7 watchdog PR).
     * Increments [com.contextsolutions.mobileagent.telemetry.CounterNames.INFERENCE_UNLOADED_WATCHDOG_TOTAL].
     */
    MainThreadWatchdog,

    /**
     * Fired by [com.contextsolutions.mobileagent.app.observability.MemoryPressureWatchdog]
     * when free system memory drops below ~1 GiB while Gemma is loaded.
     * Pre-emptive release before another app's allocation pressure pushes
     * the device into LMK / system_server reclaim — at which point an
     * uncontrolled kill is the OS's choice, not ours. PR #16.
     * Increments [com.contextsolutions.mobileagent.telemetry.CounterNames.INFERENCE_UNLOADED_LOW_MEMORY_TOTAL].
     */
    LowMemory,
}

/**
 * The lifecycle state of the loaded model. Subscribed by UI (degraded-mode
 * banner when [Loaded.activeAccelerator] is CPU; "loading…" overlay during
 * cold load; "model unloaded" hint after idle reclaim).
 */
sealed interface SessionState {
    data object Unloaded : SessionState
    data object Loading : SessionState
    data class Loaded(val activeAccelerator: Accelerator) : SessionState
    data class Failed(val message: String, val cause: Throwable?) : SessionState
}

