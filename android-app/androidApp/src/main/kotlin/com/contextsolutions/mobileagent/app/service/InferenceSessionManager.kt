package com.contextsolutions.mobileagent.app.service

import com.contextsolutions.mobileagent.inference.Accelerator
import com.contextsolutions.mobileagent.inference.GenerationEvent
import com.contextsolutions.mobileagent.inference.GenerationRequest
import com.contextsolutions.mobileagent.inference.InferenceConfig
import com.contextsolutions.mobileagent.inference.InferenceEngine
import com.contextsolutions.mobileagent.inference.MemoryHeadroomProvider
import com.contextsolutions.mobileagent.inference.ModelHandle
import com.contextsolutions.mobileagent.inference.ThermalStatus
import com.contextsolutions.mobileagent.inference.ThermalStatusProvider
import com.contextsolutions.mobileagent.telemetry.CounterNames
import com.contextsolutions.mobileagent.telemetry.NoOpTelemetryCounters
import com.contextsolutions.mobileagent.telemetry.TelemetryCounters
import javax.inject.Inject
import javax.inject.Singleton
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
    private val thermalStatusProvider: ThermalStatusProvider,
    private val counters: TelemetryCounters = NoOpTelemetryCounters,
    // PR #16 — gates eager warm-up when free RAM is below the cold-load
    // budget. Default is permissive so existing tests that construct the
    // manager directly don't have to plumb a fake; production Hilt graph
    // wires AndroidMemoryHeadroomProvider via InferenceModule.
    private val memoryHeadroomProvider: MemoryHeadroomProvider = MemoryHeadroomProvider { Long.MAX_VALUE },
) {

    /**
     * Idle timeout used after the most recent generation ends. Mutable for tests
     * (TestDispatcher / shorter timeout). Not part of the public API.
     */
    internal var idleTimeout: Duration = DEFAULT_IDLE_TIMEOUT

    /**
     * Idle timeout used after a successful [warmUpIfPossible] load. Shorter than
     * [idleTimeout] because the post-warm-up state means the user is on the chat
     * surface but hasn't actually committed to a turn — if they don't engage
     * within this window, release the GPU rather than holding it indefinitely.
     * The eager warm-up will reload on the next Chat-screen entry (1–3 s on Pixel 7).
     */
    internal var idleTimeoutAfterWarmUp: Duration = DEFAULT_IDLE_TIMEOUT_AFTER_WARMUP

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
     * M6 Phase B — eager warm-up entry point used by `MainScreen`'s
     * `LaunchedEffect(currentRoute is Chat)`. Idempotent and side-effect free
     * (no FGS start, no generation count increment): just makes sure the model
     * is loaded so the first `send()` doesn't pay the 4–8 s cold-load tax in
     * the foreground.
     *
     * Behavior:
     * - State is already `Loaded` with the same `modelPath` → returns
     *   [WarmUpOutcome.AlreadyLoaded] without touching the mutex.
     * - State is `Loading` → returns [WarmUpOutcome.AlreadyLoading] without
     *   waiting. The other loader (typically [generate]) will reach `Loaded`
     *   on its own; this method's contract is "don't duplicate work", not
     *   "guarantee a Loaded state by return".
     * - Thermal state ≥ [ThermalStatus.SEVERE] → returns
     *   [WarmUpOutcome.SkippedThermal]. The device is already throttling;
     *   spinning up Gemma would worsen the user-visible experience. The first
     *   `send()` still pays the cold load, but by then the user has chosen to
     *   accept the latency.
     * - Otherwise → acquires the mutex and runs the same `ensureLoadedLocked`
     *   path `acquire` uses, returning [WarmUpOutcome.Loaded] on success or
     *   [WarmUpOutcome.Failed] on throw. Never propagates the throw — the
     *   caller (`MainScreen` LaunchedEffect) shouldn't fail the UI on a
     *   warm-up miss; first-`send()` will retry through the normal path.
     *
     * Concurrency: safe to call from multiple coroutines in parallel. The
     * mutex serialises the load; subsequent callers see `Loaded` in the
     * fast-path and return [WarmUpOutcome.AlreadyLoaded] without blocking.
     */
    suspend fun warmUpIfPossible(
        modelPath: String,
        config: InferenceConfig = InferenceConfig(),
    ): WarmUpOutcome {
        // Fast path: skip the mutex when state is already terminal. _state is
        // updated atomically inside the lock, so a Loaded reading here is
        // guaranteed-correct. Loading is advisory — re-checked under the lock.
        when (val snapshot = _state.value) {
            is SessionState.Loaded -> {
                // Loaded with a (potentially different) model. Caller passed a
                // specific path — only declare AlreadyLoaded if it matches.
                if (handle?.modelId == modelPath) {
                    counters.increment(CounterNames.INFERENCE_WARMUP_ALREADY_LOADED_TOTAL)
                    return WarmUpOutcome.AlreadyLoaded
                }
            }
            SessionState.Loading -> {
                counters.increment(CounterNames.INFERENCE_WARMUP_ALREADY_LOADING_TOTAL)
                return WarmUpOutcome.AlreadyLoading
            }
            else -> Unit // Unloaded / Failed → fall through and try to load
        }

        val thermal = thermalStatusProvider.current()
        if (thermal.isThrottling) {
            counters.increment(CounterNames.INFERENCE_WARMUP_SKIPPED_THERMAL_TOTAL)
            return WarmUpOutcome.SkippedThermal(thermal)
        }

        // PR #16 — eager warm-up memory gate. Without this check, the
        // watchdog still catches the case (we'll unload moments after the
        // load completes), but we'd waste a full GPU init + 2.58 GB I/O
        // for nothing on memory-tight devices. Threshold matches the
        // cold-load gate in ChatViewModel so warm-up and send share one
        // floor. If we'd refuse a send anyway, refuse the warm-up too.
        val avail = memoryHeadroomProvider.availableBytes()
        if (avail < EAGER_WARMUP_MIN_FREE_BYTES) {
            counters.increment(CounterNames.INFERENCE_WARMUP_SKIPPED_MEMORY_TOTAL)
            return WarmUpOutcome.SkippedMemory(
                availableBytes = avail,
                requiredBytes = EAGER_WARMUP_MIN_FREE_BYTES,
            )
        }

        return try {
            val outcome = mutex.withLock {
                // Re-check inside the lock: another caller may have completed
                // the load while we were waiting on the mutex.
                if (handle?.modelId == modelPath && _state.value is SessionState.Loaded) {
                    WarmUpOutcome.AlreadyLoaded
                } else {
                    val loaded = ensureLoadedLocked(modelPath, config)
                    // Bound how long a warmed-up-but-unused model stays
                    // resident. Pre-watchdog the warm-up path didn't schedule
                    // any idle unload, so the model stayed loaded until the
                    // OS reaped the process or onTrimMemory fired — and on
                    // a GPU build this contributed to the system_server
                    // soft-reboot failure mode by holding the GPU
                    // indefinitely while the user wasn't actually generating.
                    // The post-generation timeout (5 min) still applies once
                    // a real turn happens; this shorter post-warmup window
                    // only fires when the user warmed up and walked away.
                    if (activeGenerationCount == 0) {
                        scheduleIdleUnloadLocked(idleTimeoutAfterWarmUp)
                    }
                    WarmUpOutcome.Loaded(loaded.activeAccelerator)
                }
            }
            when (outcome) {
                is WarmUpOutcome.Loaded -> counters.increment(CounterNames.INFERENCE_WARMUP_LOADED_TOTAL)
                WarmUpOutcome.AlreadyLoaded -> counters.increment(CounterNames.INFERENCE_WARMUP_ALREADY_LOADED_TOTAL)
                else -> Unit // unreachable from this branch
            }
            outcome
        } catch (t: Throwable) {
            counters.increment(CounterNames.INFERENCE_WARMUP_FAILED_TOTAL)
            WarmUpOutcome.Failed(t)
        }
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

    private fun scheduleIdleUnloadLocked(timeout: Duration = idleTimeout) {
        cancelIdleUnloadLocked()
        idleUnloadJob = scope.launch {
            delay(timeout)
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
        /**
         * PR #16 — minimum free system RAM required before we attempt the
         * eager warm-up cold load. 2.0 GiB tuned after on-device testing:
         * the file is ~2.58 GB on disk but the load path mmaps weight
         * pages lazily, so a 2.0 GiB free window is enough headroom to
         * succeed in practice on Pixel 7. Held here so the warm-up gate
         * and the send-time cold-load gate in
         * [com.contextsolutions.mobileagent.app.ui.chat.ChatViewModel]
         * share one threshold definition.
         */
        const val EAGER_WARMUP_MIN_FREE_BYTES: Long = 2L * 1024 * 1024 * 1024 // 2.0 GiB

        val DEFAULT_IDLE_TIMEOUT: Duration = 5.minutes
        val DEFAULT_IDLE_TIMEOUT_AFTER_WARMUP: Duration = 60.seconds
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

/**
 * Outcome of [InferenceSessionManager.warmUpIfPossible]. Drives the M6 Phase B
 * eager-load logging (and, post-Phase C, the matching telemetry counters
 * `inference_warmup_eager_total`, `inference_warmup_skipped_thermal_total`,
 * etc.). Never surfaced to UI — observers should read [InferenceSessionManager.state]
 * instead.
 */
sealed interface WarmUpOutcome {
    /** Model with the requested path was already resident — nothing to do. */
    data object AlreadyLoaded : WarmUpOutcome

    /** A load is in progress on another coroutine — let it finish; we don't duplicate work. */
    data object AlreadyLoading : WarmUpOutcome

    /** Thermal state ≥ SEVERE; skipped to avoid worsening throttle. */
    data class SkippedThermal(val status: ThermalStatus) : WarmUpOutcome

    /**
     * PR #16 — free system RAM was below
     * [InferenceSessionManager.EAGER_WARMUP_MIN_FREE_BYTES] at warm-up
     * time. Skipping the load saves a wasted GPU init + 2.58 GB I/O on a
     * device the watchdog would unload moments later anyway.
     */
    data class SkippedMemory(val availableBytes: Long, val requiredBytes: Long) : WarmUpOutcome

    /** This call performed the load. [accelerator] is what the engine actually got. */
    data class Loaded(val accelerator: Accelerator) : WarmUpOutcome

    /** Load attempted and threw; first `send()` will retry through the normal path. */
    data class Failed(val cause: Throwable) : WarmUpOutcome
}
