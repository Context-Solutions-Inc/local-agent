package com.contextsolutions.localagent.app.observability

import com.contextsolutions.localagent.app.service.AuxModelLifecycleCoordinator
import com.contextsolutions.localagent.app.service.ForegroundServiceController
import com.contextsolutions.localagent.app.service.InferenceSessionManager
import com.contextsolutions.localagent.app.service.ManagedClassifierEngine
import com.contextsolutions.localagent.app.service.ManagedEmbedderEngine
import com.contextsolutions.localagent.classifier.ClassifierAccelerator
import com.contextsolutions.localagent.classifier.ClassifierEngine
import com.contextsolutions.localagent.classifier.ClassifierOutput
import com.contextsolutions.localagent.inference.Accelerator
import com.contextsolutions.localagent.inference.FinishReason
import com.contextsolutions.localagent.inference.GenerationEvent
import com.contextsolutions.localagent.inference.GenerationRequest
import com.contextsolutions.localagent.inference.InferenceConfig
import com.contextsolutions.localagent.inference.InferenceEngine
import com.contextsolutions.localagent.inference.ModelHandle
import com.contextsolutions.localagent.inference.ThermalStatus
import com.contextsolutions.localagent.inference.ThermalStatusProvider
import com.contextsolutions.localagent.memory.EmbedderAccelerator
import com.contextsolutions.localagent.memory.EmbedderEngine
import com.contextsolutions.localagent.memory.EmbedderOutput
import com.contextsolutions.localagent.observability.SafeCrashReporter
import com.contextsolutions.localagent.telemetry.CounterNames
import com.contextsolutions.localagent.telemetry.TelemetryCounters
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [MainThreadHeartbeatWatchdog.checkForStall] synchronously by
 * controlling the [FakeMainThreadProbe.ageMs] value. Avoids spinning the
 * daemon thread so the trip logic and cooldown are deterministic.
 *
 * The session manager is the real class with no-op engine + FGS so the
 * `forceUnload(MainThreadWatchdog)` path executes end-to-end (which is
 * what increments [CounterNames.INFERENCE_UNLOADED_WATCHDOG_TOTAL]).
 * Asserting against counters is simpler and more representative than
 * wrapping the manager in a fake.
 */
class MainThreadHeartbeatWatchdogTest {

    private val probe = FakeMainThreadProbe()
    private val counters = RecordingCounters()
    private val crash = RecordingCrashReporter()
    private val sessionManager = InferenceSessionManager(
        engine = TestNoOpEngine,
        foregroundServiceController = TestNoOpFgs,
        counters = counters,
    )
    // Wrap the no-op aux engines so the watchdog → coordinator → wrapper
    // forceUnload path exercises end-to-end. Asserting against the
    // classifier/embedder counters is more representative than wrapping
    // the coordinator in a fake.
    private val managedClassifier = ManagedClassifierEngine(
        delegate = TestNoOpClassifierEngine(),
        thermalStatusProvider = TestNoOpThermal,
        counters = counters,
    )
    private val managedEmbedder = ManagedEmbedderEngine(
        delegate = TestNoOpEmbedderEngine(),
        thermalStatusProvider = TestNoOpThermal,
        counters = counters,
    )
    private val auxModelCoordinator = AuxModelLifecycleCoordinator(
        classifierEngine = managedClassifier,
        embedderEngine = managedEmbedder,
    )
    private val watchdog = MainThreadHeartbeatWatchdog(
        sessionManager = sessionManager,
        auxModelCoordinator = auxModelCoordinator,
        crashReporter = crash,
        counters = counters,
        probe = probe,
    )

    @Test
    fun `does not trip while ack age is below stall threshold`() {
        watchdog.markStartedForTest(uptimeMs = 0L)

        probe.ageMs = 5_000L  // under threshold
        watchdog.checkForStall()

        assertEquals(0L, counters.value(CounterNames.MAIN_THREAD_WATCHDOG_TRIPPED_TOTAL))
        assertEquals(0L, counters.value(CounterNames.INFERENCE_UNLOADED_WATCHDOG_TOTAL))
        assertEquals(0, crash.recordedExceptions.size)
    }

    @Test
    fun `trip at first overrun records exception and increments both counters`() {
        watchdog.markStartedForTest(
            uptimeMs = -MainThreadHeartbeatWatchdog.DEFAULT_STARTUP_GRACE_MS - 1_000L,
        )
        probe.ageMs = 25_000L
        watchdog.checkForStall()

        assertEquals(1L, counters.value(CounterNames.MAIN_THREAD_WATCHDOG_TRIPPED_TOTAL))
        assertEquals(1L, counters.value(CounterNames.INFERENCE_UNLOADED_WATCHDOG_TOTAL))
        // PR #8 — the same trip must also drop the aux models.
        assertEquals(1L, counters.value(CounterNames.CLASSIFIER_UNLOADED_WATCHDOG_TOTAL))
        assertEquals(1L, counters.value(CounterNames.EMBEDDER_UNLOADED_WATCHDOG_TOTAL))
        assertEquals(1, crash.recordedExceptions.size)
        assertTrue(crash.recordedExceptions.first() is MainThreadStallException)
        assertEquals(1, crash.flushPendingCalls)
    }

    @Test
    fun `startup grace suppresses early trips`() {
        watchdog.markStartedForTest(uptimeMs = System.nanoTime() / 1_000_000L)
        probe.ageMs = 60_000L
        watchdog.checkForStall()

        assertEquals(0L, counters.value(CounterNames.MAIN_THREAD_WATCHDOG_TRIPPED_TOTAL))
    }

    @Test
    fun `does not double-trip during cooldown window`() {
        watchdog.markStartedForTest(
            uptimeMs = -MainThreadHeartbeatWatchdog.DEFAULT_STARTUP_GRACE_MS - 1_000L,
        )
        probe.ageMs = 25_000L

        watchdog.checkForStall()
        watchdog.checkForStall()
        watchdog.checkForStall()

        assertEquals(1L, counters.value(CounterNames.MAIN_THREAD_WATCHDOG_TRIPPED_TOTAL))
        assertEquals(1, crash.recordedExceptions.size)
    }

    @Test
    fun `start is idempotent`() {
        watchdog.start()
        watchdog.start()
        watchdog.start()
        watchdog.stop()
        // AtomicBoolean gate inside start() suppresses duplicate Thread
        // spawns. Any failure would surface as either an assertion in the
        // production code, or as a thread leak that breaks subsequent
        // tests; this test exists to keep that contract under regression.
    }

    @Test
    fun `recordException throwing does not corrupt subsequent ticks`() {
        val throwingCrash = RecordingCrashReporter(recordExceptionThrows = true)
        val throwingWatchdog = MainThreadHeartbeatWatchdog(
            sessionManager = sessionManager,
            auxModelCoordinator = auxModelCoordinator,
            crashReporter = throwingCrash,
            counters = counters,
            probe = probe,
        )
        throwingWatchdog.markStartedForTest(
            uptimeMs = -MainThreadHeartbeatWatchdog.DEFAULT_STARTUP_GRACE_MS - 1_000L,
        )
        probe.ageMs = 25_000L

        throwingWatchdog.checkForStall()
        val firstRemediationAt = throwingWatchdog.lastRemediationAtUptimeMs()
        assertNotEquals(0L, firstRemediationAt)

        throwingWatchdog.checkForStall()
        // Cooldown holds despite the throw.
        assertEquals(firstRemediationAt, throwingWatchdog.lastRemediationAtUptimeMs())
    }
}

private class FakeMainThreadProbe : MainThreadProbe {
    var ageMs: Long = 0L
    var pingCount: Int = 0
    override fun pingMainThread() { pingCount++ }
    override fun ageOfLastAckMs(): Long = ageMs
}

private object TestNoOpEngine : InferenceEngine {
    override suspend fun loadModel(modelPath: String, config: InferenceConfig): ModelHandle =
        object : ModelHandle {
            override val modelId = modelPath
            override val loadedAtEpochMs = 0L
            override val activeAccelerator = Accelerator.GPU
        }
    override fun unload(handle: ModelHandle) = Unit
    override fun generate(
        handle: ModelHandle,
        request: GenerationRequest,
        toolDispatcher: com.contextsolutions.localagent.inference.ToolDispatcher?,
    ): Flow<GenerationEvent> = flow {
        emit(GenerationEvent.Done(0, FinishReason.END_OF_TURN))
    }
}

private object TestNoOpFgs : ForegroundServiceController {
    override fun start() = Unit
    override fun stop() = Unit
}

private object TestNoOpThermal : ThermalStatusProvider {
    private val state = MutableStateFlow(ThermalStatus.NONE)
    override fun current(): ThermalStatus = ThermalStatus.NONE
    override fun statusFlow() = state.asStateFlow()
}

private class TestNoOpClassifierEngine : ClassifierEngine {
    @Volatile private var loaded = false
    override val isLoaded: Boolean get() = loaded
    override suspend fun warmUp(): ClassifierAccelerator? {
        loaded = true
        return ClassifierAccelerator.CPU
    }
    override suspend fun classify(inputIds: LongArray, attentionMask: LongArray): ClassifierOutput? = null
    override suspend fun unload() { loaded = false }
}

private class TestNoOpEmbedderEngine : EmbedderEngine {
    @Volatile private var loaded = false
    override val isLoaded: Boolean get() = loaded
    override suspend fun warmUp(): EmbedderAccelerator? {
        loaded = true
        return EmbedderAccelerator.CPU
    }
    override suspend fun embed(text: String): EmbedderOutput? = null
    override suspend fun unload() { loaded = false }
}

private class RecordingCrashReporter(
    val recordExceptionThrows: Boolean = false,
) : SafeCrashReporter {
    val recordedExceptions = mutableListOf<Throwable>()
    var flushPendingCalls = 0

    override fun recordException(throwable: Throwable, context: Map<String, String>) {
        recordedExceptions += throwable
        if (recordExceptionThrows) throw RuntimeException("simulated crashlytics failure")
    }
    override fun log(message: String) = Unit
    override fun setCustomKey(key: String, value: String) = Unit
    override fun setCollectionEnabled(enabled: Boolean) = Unit
    override fun flushPending() { flushPendingCalls++ }
}

private class RecordingCounters : TelemetryCounters {
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    override fun increment(name: String, by: Long) {
        counters.computeIfAbsent(name) { AtomicLong(0) }.addAndGet(by)
    }
    override fun observeLatency(metric: String, durationMs: Long) = Unit
    fun value(name: String): Long = counters[name]?.get() ?: 0L
}
