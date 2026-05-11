package com.contextsolutions.mobileagent.app.service

import app.cash.turbine.test
import com.contextsolutions.mobileagent.inference.Accelerator
import com.contextsolutions.mobileagent.inference.FinishReason
import com.contextsolutions.mobileagent.inference.GenerationEvent
import com.contextsolutions.mobileagent.inference.GenerationRequest
import com.contextsolutions.mobileagent.inference.InferenceConfig
import com.contextsolutions.mobileagent.inference.InferenceEngine
import com.contextsolutions.mobileagent.inference.ModelHandle
import com.contextsolutions.mobileagent.inference.ThermalStatus
import com.contextsolutions.mobileagent.inference.ThermalStatusProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
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
    ): InferenceSessionManager {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = InferenceSessionManager(engine, fgs, thermal)
        manager.idleTimeout = IDLE_TIMEOUT
        manager.ioDispatcher = dispatcher
        manager.scope = CoroutineScope(SupervisorJob() + dispatcher)
        return manager
    }

    private companion object {
        const val MODEL_PATH = "/data/test/model.litertlm"
        val IDLE_TIMEOUT = 1.minutes
        val REQUEST = GenerationRequest(prompt = "hi")
    }
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
