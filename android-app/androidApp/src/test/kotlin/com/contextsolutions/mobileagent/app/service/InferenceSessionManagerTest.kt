package com.contextsolutions.mobileagent.app.service

import app.cash.turbine.test
import com.contextsolutions.mobileagent.inference.Accelerator
import com.contextsolutions.mobileagent.inference.FinishReason
import com.contextsolutions.mobileagent.inference.GenerationEvent
import com.contextsolutions.mobileagent.inference.GenerationRequest
import com.contextsolutions.mobileagent.inference.InferenceConfig
import com.contextsolutions.mobileagent.inference.InferenceEngine
import com.contextsolutions.mobileagent.inference.MemoryHeadroomProvider
import com.contextsolutions.mobileagent.inference.ModelHandle
import com.contextsolutions.mobileagent.inference.ThermalStatus
import com.contextsolutions.mobileagent.inference.ThermalStatusProvider
import com.contextsolutions.mobileagent.telemetry.CounterNames
import com.contextsolutions.mobileagent.telemetry.TelemetryCounters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the lifecycle invariants on [InferenceSessionManager] without spinning
 * up Robolectric. The FGS interaction is verified through [FakeForegroundServiceController];
 * load/unload accounting through [FakeInferenceEngine].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InferenceSessionManagerTest {

    @Test
    fun `acquire loads model and emits Loading then Loaded`() = runTest {
        val (manager, engine, _) = newManager()

        manager.state.test {
            assertEquals(SessionState.Unloaded, awaitItem())
            val handle = manager.acquire(MODEL_PATH)
            assertEquals(SessionState.Loading, awaitItem())
            assertEquals(SessionState.Loaded(Accelerator.GPU), awaitItem())
            assertEquals(MODEL_PATH, handle.modelId)
        }
        assertEquals(1, engine.loadCount.get())
    }

    @Test
    fun `repeat acquire of same path does not reload`() = runTest {
        val (manager, engine, _) = newManager()
        manager.acquire(MODEL_PATH)
        manager.acquire(MODEL_PATH)
        assertEquals(1, engine.loadCount.get())
    }

    @Test
    fun `generate starts foreground service and stops it after stream completes`() = runTest {
        val (manager, _, fgs) = newManager()

        val events = mutableListOf<GenerationEvent>()
        manager.generate(MODEL_PATH, REQUEST).collect { events += it }

        assertTrue(events.any { it is GenerationEvent.TokenChunk })
        assertTrue(events.last() is GenerationEvent.Done)
        assertEquals(1, fgs.startCount.get())
        assertEquals(1, fgs.stopCount.get())
    }

    @Test
    fun `idle unload fires after timeout when generation has ended`() = runTest {
        val (manager, engine, _) = newManager()
        manager.generate(MODEL_PATH, REQUEST).collect { /* drain */ }
        // Just before the timeout: still loaded.
        advanceTimeBy(IDLE_TIMEOUT.inWholeMilliseconds - 1)
        assertEquals(0, engine.unloadCount.get())
        // After the timeout: unloaded.
        advanceTimeBy(2)
        advanceUntilIdle()
        assertEquals(1, engine.unloadCount.get())
        assertEquals(SessionState.Unloaded, manager.state.value)
    }

    @Test
    fun `next generate after idle unload reloads the model`() = runTest {
        val (manager, engine, _) = newManager()
        manager.generate(MODEL_PATH, REQUEST).collect { }
        advanceTimeBy(IDLE_TIMEOUT.inWholeMilliseconds + 1)
        advanceUntilIdle()
        assertEquals(1, engine.unloadCount.get())

        manager.generate(MODEL_PATH, REQUEST).collect { }
        assertEquals(2, engine.loadCount.get())
    }

    @Test
    fun `forceUnload while idle unloads immediately`() = runTest {
        val (manager, engine, _) = newManager()
        manager.acquire(MODEL_PATH)
        manager.forceUnload()
        advanceUntilIdle()
        assertEquals(1, engine.unloadCount.get())
        assertEquals(SessionState.Unloaded, manager.state.value)
    }

    @Test
    fun `forceUnload during generation defers unload until generation ends`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = FakeInferenceEngine(generateOverride = { _, _ ->
            flow {
                emit(GenerationEvent.TokenChunk("x", 0))
                gate.await()
                emit(GenerationEvent.Done(1, FinishReason.END_OF_TURN))
            }
        })
        val fgs = FakeForegroundServiceController()
        val manager = newManagerWith(engine, fgs)

        val collectJob = launch {
            manager.generate(MODEL_PATH, REQUEST).collect { /* drain */ }
        }
        advanceUntilIdle()  // Let load + first emission complete.
        assertEquals(1, fgs.startCount.get())

        // Force unload while generation is still in flight: must NOT unload yet.
        manager.forceUnload()
        advanceUntilIdle()
        assertEquals(0, engine.unloadCount.get())

        // Allow generation to complete.
        gate.complete(Unit)
        collectJob.join()
        advanceUntilIdle()
        assertEquals(1, engine.unloadCount.get())
        assertEquals(SessionState.Unloaded, manager.state.value)
    }

    @Test
    fun `loaded state reflects activeAccelerator from engine`() = runTest {
        val engine = FakeInferenceEngine(loadAccelerator = Accelerator.CPU)
        val manager = newManagerWith(engine, FakeForegroundServiceController())
        manager.acquire(MODEL_PATH)
        assertEquals(SessionState.Loaded(Accelerator.CPU), manager.state.value)
    }

    @Test
    fun `cancelling collector during generation still drops fgs and schedules unload`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = FakeInferenceEngine(generateOverride = { _, _ ->
            flow {
                emit(GenerationEvent.TokenChunk("x", 0))
                gate.await()
                emit(GenerationEvent.Done(1, FinishReason.END_OF_TURN))
            }
        })
        val fgs = FakeForegroundServiceController()
        val manager = newManagerWith(engine, fgs)

        val collectJob = launch {
            manager.generate(MODEL_PATH, REQUEST).collect { }
        }
        advanceUntilIdle()
        collectJob.cancel()
        collectJob.join()
        // runCurrent drains the NonCancellable cleanup block without advancing
        // virtual time past the idle timer, so we can observe the intermediate
        // state where FGS has stopped but the model is still loaded.
        runCurrent()

        assertEquals(1, fgs.stopCount.get())
        assertEquals(0, engine.unloadCount.get())
        advanceTimeBy(IDLE_TIMEOUT.inWholeMilliseconds + 1)
        advanceUntilIdle()
        assertEquals(1, engine.unloadCount.get())
    }

    // ─── M6 Phase B: warmUpIfPossible ─────────────────────────────────────

    @Test
    fun `warmUpIfPossible loads the model and returns Loaded`() = runTest {
        val (manager, engine, fgs) = newManager()

        val outcome = manager.warmUpIfPossible(MODEL_PATH)

        assertTrue("expected Loaded, got $outcome", outcome is WarmUpOutcome.Loaded)
        assertEquals(Accelerator.GPU, (outcome as WarmUpOutcome.Loaded).accelerator)
        assertEquals(1, engine.loadCount.get())
        // Warm-up must NOT start the foreground service — only an actual
        // generation does. Confirms the path doesn't trip FGS state.
        assertEquals(0, fgs.startCount.get())
        assertEquals(SessionState.Loaded(Accelerator.GPU), manager.state.value)
    }

    @Test
    fun `warmUpIfPossible returns AlreadyLoaded when model already loaded`() = runTest {
        val (manager, engine, _) = newManager()
        manager.acquire(MODEL_PATH)
        assertEquals(1, engine.loadCount.get())

        val outcome = manager.warmUpIfPossible(MODEL_PATH)

        assertEquals(WarmUpOutcome.AlreadyLoaded, outcome)
        // Critically: no second load attempt — the fast path short-circuits.
        assertEquals(1, engine.loadCount.get())
    }

    @Test
    fun `warmUpIfPossible returns SkippedThermal at SEVERE`() = runTest {
        val engine = FakeInferenceEngine()
        val fgs = FakeForegroundServiceController()
        val thermal = FakeThermalStatusProvider(initial = ThermalStatus.SEVERE)
        val manager = newManagerWith(engine, fgs, thermal)

        val outcome = manager.warmUpIfPossible(MODEL_PATH)

        assertTrue("expected SkippedThermal, got $outcome", outcome is WarmUpOutcome.SkippedThermal)
        assertEquals(ThermalStatus.SEVERE, (outcome as WarmUpOutcome.SkippedThermal).status)
        assertEquals(0, engine.loadCount.get())
        assertEquals(SessionState.Unloaded, manager.state.value)
    }

    @Test
    fun `warmUpIfPossible returns SkippedThermal at CRITICAL`() = runTest {
        val engine = FakeInferenceEngine()
        val fgs = FakeForegroundServiceController()
        val thermal = FakeThermalStatusProvider(initial = ThermalStatus.CRITICAL)
        val manager = newManagerWith(engine, fgs, thermal)

        val outcome = manager.warmUpIfPossible(MODEL_PATH)

        assertTrue("expected SkippedThermal, got $outcome", outcome is WarmUpOutcome.SkippedThermal)
        assertEquals(0, engine.loadCount.get())
    }

    @Test
    fun `warmUpIfPossible proceeds at MODERATE thermal`() = runTest {
        val engine = FakeInferenceEngine()
        val fgs = FakeForegroundServiceController()
        val thermal = FakeThermalStatusProvider(initial = ThermalStatus.MODERATE)
        val manager = newManagerWith(engine, fgs, thermal)

        val outcome = manager.warmUpIfPossible(MODEL_PATH)

        // MODERATE is below the SEVERE throttle threshold — load proceeds.
        assertTrue("expected Loaded at MODERATE, got $outcome", outcome is WarmUpOutcome.Loaded)
        assertEquals(1, engine.loadCount.get())
    }

    @Test
    fun `warmUpIfPossible returns Failed and does not throw when engine throws`() = runTest {
        val throwingEngine = object : InferenceEngine {
            override suspend fun loadModel(modelPath: String, config: InferenceConfig): ModelHandle {
                throw IllegalStateException("simulated cold-load failure")
            }
            override fun unload(handle: ModelHandle) = Unit
            override fun generate(
                handle: ModelHandle,
                request: GenerationRequest,
                toolDispatcher: com.contextsolutions.mobileagent.inference.ToolDispatcher?,
            ): Flow<GenerationEvent> = flow { }
        }
        val fgs = FakeForegroundServiceController()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = InferenceSessionManager(throwingEngine, fgs, FakeThermalStatusProvider())
        manager.idleTimeout = IDLE_TIMEOUT
        manager.ioDispatcher = dispatcher
        manager.scope = CoroutineScope(SupervisorJob() + dispatcher)

        val outcome = manager.warmUpIfPossible(MODEL_PATH)

        // Caller (MainScreen LaunchedEffect) must not see a throw — would crash
        // the UI. Failure is surfaced via the outcome instead.
        assertTrue("expected Failed, got $outcome", outcome is WarmUpOutcome.Failed)
        assertTrue((outcome as WarmUpOutcome.Failed).cause is IllegalStateException)
        // Manager state still reflects the failure for any observer.
        assertTrue(manager.state.value is SessionState.Failed)
    }

    @Test
    fun `warmUpIfPossible is idempotent under concurrent calls`() = runTest {
        val (manager, engine, _) = newManager()

        // Fire 3 calls concurrently — only one underlying load should happen.
        val jobs = (1..3).map {
            launch { manager.warmUpIfPossible(MODEL_PATH) }
        }
        jobs.forEach { it.join() }

        assertEquals(1, engine.loadCount.get())
        assertEquals(SessionState.Loaded(Accelerator.GPU), manager.state.value)
    }

    // ─── M7 watchdog PR: post-warmup idle + new UnloadReason ──────────────

    @Test
    fun `warmUpIfPossible schedules idle unload after the shorter post-warmup timeout`() = runTest {
        val (manager, engine, _) = newManager()
        manager.idleTimeoutAfterWarmUp = POST_WARMUP_IDLE_TIMEOUT

        manager.warmUpIfPossible(MODEL_PATH)
        // runCurrent() drains immediately-pending tasks (the scheduling itself)
        // without advancing virtual time, so we can observe the
        // "loaded but timer not yet fired" intermediate state.
        runCurrent()
        assertEquals(0, engine.unloadCount.get())

        // Just before the shorter post-warmup timeout: still loaded.
        advanceTimeBy(POST_WARMUP_IDLE_TIMEOUT.inWholeMilliseconds - 1)
        runCurrent()
        assertEquals(0, engine.unloadCount.get())

        // After the timeout: unloaded.
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, engine.unloadCount.get())
        assertEquals(SessionState.Unloaded, manager.state.value)
    }

    @Test
    fun `generate after warm-up resets idle timer to post-generation timeout`() = runTest {
        val (manager, engine, _) = newManager()
        manager.idleTimeoutAfterWarmUp = POST_WARMUP_IDLE_TIMEOUT

        manager.warmUpIfPossible(MODEL_PATH)
        manager.generate(MODEL_PATH, REQUEST).collect { /* drain */ }
        runCurrent()

        // Wait past the post-warmup window — should NOT unload, because the
        // generation should have switched us to the longer post-generation
        // timer (cancelIdleUnloadLocked inside generate).
        advanceTimeBy(POST_WARMUP_IDLE_TIMEOUT.inWholeMilliseconds + 100)
        runCurrent()
        assertEquals(0, engine.unloadCount.get())

        // The post-generation timer is the IDLE_TIMEOUT (1.minute in tests,
        // 5.minutes in prod). Advance past it.
        advanceTimeBy(IDLE_TIMEOUT.inWholeMilliseconds + 1)
        runCurrent()
        assertEquals(1, engine.unloadCount.get())
    }

    @Test
    fun `forceUnload with MainThreadWatchdog increments the dedicated counter`() = runTest {
        val counters = RecordingCounters()
        val manager = newManagerWith(
            FakeInferenceEngine(),
            FakeForegroundServiceController(),
            counters = counters,
        )
        manager.acquire(MODEL_PATH)

        manager.forceUnload(UnloadReason.MainThreadWatchdog)
        advanceUntilIdle()

        assertEquals(
            1L,
            counters.value(CounterNames.INFERENCE_UNLOADED_WATCHDOG_TOTAL),
        )
        // TrimMemory must not double-count from a watchdog trip.
        assertEquals(
            0L,
            counters.value(CounterNames.INFERENCE_UNLOADED_TRIM_MEMORY_TOTAL),
        )
    }

    @Test
    fun `warmUpIfPossible skips load and increments counter when free memory is below threshold`() = runTest {
        // PR #16 — eager warm-up memory gate. The repro from on-device
        // testing: 8 GB Pixel 7 with other apps consuming RAM, eager
        // warm-up always proceeded and then got immediately undone by the
        // watchdog. Wasteful and confusing. This test locks the gate.
        val counters = RecordingCounters()
        val engine = FakeInferenceEngine()
        val tightProvider = MemoryHeadroomProvider { 800L * 1024 * 1024 } // 800 MB
        val manager = newManagerWith(
            engine,
            FakeForegroundServiceController(),
            counters = counters,
            memoryHeadroomProvider = tightProvider,
        )

        val outcome = manager.warmUpIfPossible(MODEL_PATH)
        advanceUntilIdle()

        assertTrue(
            "expected SkippedMemory outcome, got $outcome",
            outcome is WarmUpOutcome.SkippedMemory,
        )
        assertEquals(0, engine.loadCount.get())
        assertEquals(1L, counters.value(CounterNames.INFERENCE_WARMUP_SKIPPED_MEMORY_TOTAL))
        assertEquals(0L, counters.value(CounterNames.INFERENCE_WARMUP_LOADED_TOTAL))
    }

    @Test
    fun `warmUpIfPossible loads when free memory is above threshold`() = runTest {
        val counters = RecordingCounters()
        val engine = FakeInferenceEngine()
        val plentyProvider = MemoryHeadroomProvider { 4_000L * 1024 * 1024 } // 4 GB
        val manager = newManagerWith(
            engine,
            FakeForegroundServiceController(),
            counters = counters,
            memoryHeadroomProvider = plentyProvider,
        )

        val outcome = manager.warmUpIfPossible(MODEL_PATH)
        advanceUntilIdle()

        assertTrue("expected Loaded outcome, got $outcome", outcome is WarmUpOutcome.Loaded)
        assertEquals(1, engine.loadCount.get())
        assertEquals(1L, counters.value(CounterNames.INFERENCE_WARMUP_LOADED_TOTAL))
        assertEquals(0L, counters.value(CounterNames.INFERENCE_WARMUP_SKIPPED_MEMORY_TOTAL))
    }

    @Test
    fun `forceUnload with LowMemory increments only the LowMemory counter`() = runTest {
        // PR #16 — the MemoryPressureWatchdog needs its own counter so we
        // can separate "OS told us to free memory" (TrimMemory) from "we
        // pre-emptively unloaded based on our own headroom poll" (LowMemory)
        // in the telemetry dashboards.
        val counters = RecordingCounters()
        val manager = newManagerWith(
            FakeInferenceEngine(),
            FakeForegroundServiceController(),
            counters = counters,
        )
        manager.acquire(MODEL_PATH)

        manager.forceUnload(UnloadReason.LowMemory)
        advanceUntilIdle()

        assertEquals(
            1L,
            counters.value(CounterNames.INFERENCE_UNLOADED_LOW_MEMORY_TOTAL),
        )
        assertEquals(
            0L,
            counters.value(CounterNames.INFERENCE_UNLOADED_TRIM_MEMORY_TOTAL),
        )
        assertEquals(
            0L,
            counters.value(CounterNames.INFERENCE_UNLOADED_WATCHDOG_TOTAL),
        )
    }

    @Test
    fun `forceUnload with LowMemory during generation defers unload until generation ends`() = runTest {
        // PR #16 — matches the existing defer-mid-generation contract. The
        // watchdog firing on a 1 GiB threshold must not abort a turn the
        // user is actively waiting on; the unload happens when the active
        // generation count drops to 0.
        val gate = CompletableDeferred<Unit>()
        val engine = FakeInferenceEngine(generateOverride = { _, _ ->
            flow {
                emit(GenerationEvent.TokenChunk("x", 0))
                gate.await()
                emit(GenerationEvent.Done(1, FinishReason.END_OF_TURN))
            }
        })
        val fgs = FakeForegroundServiceController()
        val manager = newManagerWith(engine, fgs)

        val collectJob = launch {
            manager.generate(MODEL_PATH, REQUEST).collect { /* drain */ }
        }
        advanceUntilIdle()

        manager.forceUnload(UnloadReason.LowMemory)
        advanceUntilIdle()
        assertEquals("must not unload mid-generation", 0, engine.unloadCount.get())

        gate.complete(Unit)
        collectJob.join()
        advanceUntilIdle()
        assertEquals(1, engine.unloadCount.get())
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private fun TestScope.newManager(): Triple<InferenceSessionManager, FakeInferenceEngine, FakeForegroundServiceController> {
        val engine = FakeInferenceEngine()
        val fgs = FakeForegroundServiceController()
        return Triple(newManagerWith(engine, fgs), engine, fgs)
    }

    private fun TestScope.newManagerWith(
        engine: FakeInferenceEngine,
        fgs: FakeForegroundServiceController,
        thermal: ThermalStatusProvider = FakeThermalStatusProvider(),
        counters: TelemetryCounters = com.contextsolutions.mobileagent.telemetry.NoOpTelemetryCounters,
        memoryHeadroomProvider: MemoryHeadroomProvider = MemoryHeadroomProvider { Long.MAX_VALUE },
    ): InferenceSessionManager {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = InferenceSessionManager(
            engine = engine,
            foregroundServiceController = fgs,
            thermalStatusProvider = thermal,
            counters = counters,
            memoryHeadroomProvider = memoryHeadroomProvider,
        )
        manager.idleTimeout = IDLE_TIMEOUT
        manager.ioDispatcher = dispatcher
        manager.scope = CoroutineScope(SupervisorJob() + dispatcher)
        return manager
    }

    private companion object {
        const val MODEL_PATH = "/data/test/model.litertlm"
        val IDLE_TIMEOUT = 1.minutes
        val POST_WARMUP_IDLE_TIMEOUT = 15.seconds
        val REQUEST = GenerationRequest(prompt = "hi")
    }
}

/**
 * Trivial recording impl that captures every call into a map for
 * assertion. Latency observations are recorded as the count of samples;
 * we don't care about the values in these tests.
 */
private class RecordingCounters : TelemetryCounters {
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val latencies = ConcurrentHashMap<String, AtomicLong>()

    override fun increment(name: String, by: Long) {
        counters.computeIfAbsent(name) { AtomicLong(0) }.addAndGet(by)
    }

    override fun observeLatency(metric: String, durationMs: Long) {
        latencies.computeIfAbsent(metric) { AtomicLong(0) }.incrementAndGet()
    }

    fun value(name: String): Long = counters[name]?.get() ?: 0L
}

private class FakeInferenceEngine(
    private val loadAccelerator: Accelerator = Accelerator.GPU,
    private val generateOverride: ((ModelHandle, GenerationRequest) -> Flow<GenerationEvent>)? = null,
) : InferenceEngine {

    val loadCount = AtomicInteger(0)
    val unloadCount = AtomicInteger(0)

    override suspend fun loadModel(modelPath: String, config: InferenceConfig): ModelHandle {
        loadCount.incrementAndGet()
        return FakeHandle(
            modelId = modelPath,
            loadedAtEpochMs = 0L,
            activeAccelerator = loadAccelerator,
        )
    }

    override fun unload(handle: ModelHandle) {
        unloadCount.incrementAndGet()
    }

    override fun generate(
        handle: ModelHandle,
        request: GenerationRequest,
        toolDispatcher: com.contextsolutions.mobileagent.inference.ToolDispatcher?,
    ): Flow<GenerationEvent> =
        generateOverride?.invoke(handle, request) ?: flow {
            emit(GenerationEvent.TokenChunk("hello", 0))
            emit(GenerationEvent.Done(1, FinishReason.END_OF_TURN))
        }

    private data class FakeHandle(
        override val modelId: String,
        override val loadedAtEpochMs: Long,
        override val activeAccelerator: Accelerator,
    ) : ModelHandle
}

private class FakeForegroundServiceController : ForegroundServiceController {
    val startCount = AtomicInteger(0)
    val stopCount = AtomicInteger(0)
    override fun start() { startCount.incrementAndGet() }
    override fun stop() { stopCount.incrementAndGet() }
}

/**
 * Deterministic [ThermalStatusProvider] for unit tests. Default state is
 * [ThermalStatus.NONE]; tests that exercise the thermal gate in
 * `warmUpIfPossible` flip it to SEVERE/CRITICAL via [setStatus].
 */
private class FakeThermalStatusProvider(
    initial: ThermalStatus = ThermalStatus.NONE,
) : ThermalStatusProvider {
    private val state = MutableStateFlow(initial)
    override fun current(): ThermalStatus = state.value
    override fun statusFlow() = state.asStateFlow()
    fun setStatus(status: ThermalStatus) { state.value = status }
}
