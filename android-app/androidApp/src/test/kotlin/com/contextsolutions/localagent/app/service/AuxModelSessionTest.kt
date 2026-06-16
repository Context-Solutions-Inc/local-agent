package com.contextsolutions.localagent.app.service

import com.contextsolutions.localagent.inference.ThermalStatus
import com.contextsolutions.localagent.inference.ThermalStatusProvider
import com.contextsolutions.localagent.telemetry.CounterNames
import com.contextsolutions.localagent.telemetry.TelemetryCounters
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lifecycle invariants for [AuxModelSession]. Mirrors
 * [InferenceSessionManagerTest] against a fake `warmUp` / `unload` lambda
 * so we don't need Robolectric.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuxModelSessionTest {

    @Test
    fun `withSession loads engine on first call`() = runTest {
        val (session, fake, _) = newSession()

        session.withSession { fake.markUsed() }

        assertEquals(1, fake.warmUpCount.get())
        assertEquals(1, fake.useCount.get())
        assertTrue(session.isLoaded)
    }

    @Test
    fun `repeat withSession of loaded engine does not reload`() = runTest {
        val (session, fake, _) = newSession()

        session.withSession { fake.markUsed() }
        session.withSession { fake.markUsed() }

        assertEquals(1, fake.warmUpCount.get())
        assertEquals(2, fake.useCount.get())
    }

    @Test
    fun `idle unload fires after timeout when use has ended`() = runTest {
        val (session, fake, _) = newSession()
        session.withSession { fake.markUsed() }

        advanceTimeBy(IDLE_TIMEOUT.inWholeMilliseconds - 1)
        runCurrent()
        assertEquals(0, fake.unloadCount.get())

        advanceTimeBy(2)
        advanceUntilIdle()
        assertEquals(1, fake.unloadCount.get())
        assertEquals(false, session.isLoaded)
    }

    @Test
    fun `next withSession after idle unload reloads the engine`() = runTest {
        val (session, fake, _) = newSession()
        session.withSession { fake.markUsed() }
        advanceTimeBy(IDLE_TIMEOUT.inWholeMilliseconds + 1)
        advanceUntilIdle()
        assertEquals(1, fake.unloadCount.get())

        session.withSession { fake.markUsed() }

        assertEquals(2, fake.warmUpCount.get())
        assertTrue(session.isLoaded)
    }

    @Test
    fun `forceUnload while idle unloads immediately`() = runTest {
        val (session, fake, _) = newSession()
        session.withSession { fake.markUsed() }
        // Drain the scheduled idle-unload coroutine so it doesn't race the
        // forceUnload below — we only want forceUnload to drive the unload.
        runCurrent()
        assertEquals(0, fake.unloadCount.get())

        session.forceUnload(UnloadReason.TrimMemory)
        advanceUntilIdle()

        assertEquals(1, fake.unloadCount.get())
        assertEquals(false, session.isLoaded)
    }

    @Test
    fun `forceUnload during active use defers unload until use ends`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val (session, fake, _) = newSession()

        val callJob = launch {
            session.withSession {
                fake.markUsed()
                gate.await()
                "ok"
            }
        }
        // Let withSession's mutex section complete + the use start.
        advanceUntilIdle()

        // forceUnload while in flight: should NOT unload yet.
        session.forceUnload(UnloadReason.TrimMemory)
        advanceUntilIdle()
        assertEquals(0, fake.unloadCount.get())
        assertTrue(session.isLoaded)

        // Release the call → the deferred unload fires.
        gate.complete(Unit)
        callJob.join()
        advanceUntilIdle()
        assertEquals(1, fake.unloadCount.get())
        assertEquals(false, session.isLoaded)
    }

    @Test
    fun `forceUnload with TrimMemory increments the dedicated counter`() = runTest {
        val counters = AuxRecordingCounters()
        val (session, _, _) = newSession(counters = counters)
        session.withSession { /* load */ }
        runCurrent()

        session.forceUnload(UnloadReason.TrimMemory)
        advanceUntilIdle()

        assertEquals(1L, counters.value(CounterNames.CLASSIFIER_UNLOADED_TRIM_MEMORY_TOTAL))
        assertEquals(0L, counters.value(CounterNames.CLASSIFIER_UNLOADED_WATCHDOG_TOTAL))
    }

    @Test
    fun `forceUnload with MainThreadWatchdog increments the dedicated counter`() = runTest {
        val counters = AuxRecordingCounters()
        val (session, _, _) = newSession(counters = counters)
        session.withSession { /* load */ }
        runCurrent()

        session.forceUnload(UnloadReason.MainThreadWatchdog)
        advanceUntilIdle()

        assertEquals(1L, counters.value(CounterNames.CLASSIFIER_UNLOADED_WATCHDOG_TOTAL))
        assertEquals(0L, counters.value(CounterNames.CLASSIFIER_UNLOADED_TRIM_MEMORY_TOTAL))
    }

    @Test
    fun `idle unload fires the idle counter`() = runTest {
        val counters = AuxRecordingCounters()
        val (session, _, _) = newSession(counters = counters)
        session.withSession { /* load + use */ }
        advanceTimeBy(IDLE_TIMEOUT.inWholeMilliseconds + 1)
        advanceUntilIdle()

        assertEquals(1L, counters.value(CounterNames.CLASSIFIER_UNLOADED_IDLE_TOTAL))
    }

    // ─── warmUpIfPossible ────────────────────────────────────────────────

    @Test
    fun `warmUpIfPossible loads engine and returns Loaded`() = runTest {
        val (session, fake, _) = newSession()

        val outcome = session.warmUpIfPossible()

        assertTrue("expected Loaded, got $outcome", outcome is AuxWarmUpOutcome.Loaded)
        assertEquals(1, fake.warmUpCount.get())
        assertTrue(session.isLoaded)
    }

    @Test
    fun `warmUpIfPossible returns AlreadyLoaded when engine already loaded`() = runTest {
        val (session, fake, _) = newSession()
        session.withSession { /* load */ }
        assertEquals(1, fake.warmUpCount.get())

        val outcome = session.warmUpIfPossible()

        assertEquals(AuxWarmUpOutcome.AlreadyLoaded, outcome)
        assertEquals(1, fake.warmUpCount.get())
    }

    @Test
    fun `warmUpIfPossible returns SkippedThermal at SEVERE`() = runTest {
        val thermal = AuxFakeThermalStatusProvider(initial = ThermalStatus.SEVERE)
        val (session, fake, _) = newSession(thermal = thermal)

        val outcome = session.warmUpIfPossible()

        assertTrue("expected SkippedThermal, got $outcome", outcome is AuxWarmUpOutcome.SkippedThermal)
        assertEquals(ThermalStatus.SEVERE, (outcome as AuxWarmUpOutcome.SkippedThermal).status)
        assertEquals(0, fake.warmUpCount.get())
        assertEquals(false, session.isLoaded)
    }

    @Test
    fun `warmUpIfPossible at SEVERE increments the skipped counter`() = runTest {
        val counters = AuxRecordingCounters()
        val thermal = AuxFakeThermalStatusProvider(initial = ThermalStatus.SEVERE)
        val (session, _, _) = newSession(thermal = thermal, counters = counters)

        session.warmUpIfPossible()

        assertEquals(1L, counters.value(CounterNames.CLASSIFIER_WARMUP_SKIPPED_THERMAL_TOTAL))
    }

    @Test
    fun `warmUpIfPossible proceeds at MODERATE thermal`() = runTest {
        val thermal = AuxFakeThermalStatusProvider(initial = ThermalStatus.MODERATE)
        val (session, fake, _) = newSession(thermal = thermal)

        val outcome = session.warmUpIfPossible()

        assertTrue("expected Loaded at MODERATE, got $outcome", outcome is AuxWarmUpOutcome.Loaded)
        assertEquals(1, fake.warmUpCount.get())
    }

    @Test
    fun `warmUpIfPossible returns Failed and does not throw when warmUp fails`() = runTest {
        val counters = AuxRecordingCounters()
        val (session, _, _) = newSession(
            warmUpResult = false,
            counters = counters,
        )

        val outcome = session.warmUpIfPossible()

        assertTrue("expected Failed, got $outcome", outcome is AuxWarmUpOutcome.Failed)
        assertEquals(1L, counters.value(CounterNames.CLASSIFIER_WARMUP_FAILED_TOTAL))
        assertEquals(false, session.isLoaded)
    }

    @Test
    fun `warmUpIfPossible schedules the shorter post-warmup idle when no use follows`() = runTest {
        val (session, fake, _) = newSession()
        session.idleTimeoutAfterWarmUp = POST_WARMUP_IDLE_TIMEOUT

        session.warmUpIfPossible()
        runCurrent()
        assertEquals(0, fake.unloadCount.get())

        advanceTimeBy(POST_WARMUP_IDLE_TIMEOUT.inWholeMilliseconds - 1)
        runCurrent()
        assertEquals(0, fake.unloadCount.get())

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, fake.unloadCount.get())
    }

    @Test
    fun `withSession after warmUp resets idle timer to longer post-use timeout`() = runTest {
        val (session, fake, _) = newSession()
        session.idleTimeoutAfterWarmUp = POST_WARMUP_IDLE_TIMEOUT

        session.warmUpIfPossible()
        session.withSession { /* real use */ }
        runCurrent()

        // Past the short post-warmup window — must NOT unload, because
        // withSession's onUseEnded should have switched to the longer
        // post-use timer.
        advanceTimeBy(POST_WARMUP_IDLE_TIMEOUT.inWholeMilliseconds + 100)
        runCurrent()
        assertEquals(0, fake.unloadCount.get())

        // Past the post-use timer: now it unloads.
        advanceTimeBy(IDLE_TIMEOUT.inWholeMilliseconds + 1)
        runCurrent()
        assertEquals(1, fake.unloadCount.get())
    }

    @Test
    fun `withSession passes through delegate failure as null without crashing`() = runTest {
        // When the underlying engine.warmUp returns false (model load
        // fails), withSession still invokes the block — the delegate's
        // own classify()/embed() guard returns null. Verify the wrapper
        // doesn't throw and doesn't increment active count bookkeeping.
        val (session, fake, _) = newSession(warmUpResult = false)

        val result: String? = session.withSession {
            fake.markUsed()
            null
        }

        assertNull(result)
        assertEquals(false, session.isLoaded)
        // The block was still called — delegate's existing degradation
        // path handles it. We didn't bump activeUseCount so a stray
        // idle timer wasn't scheduled either.
        assertEquals(1, fake.useCount.get())
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private fun TestScope.newSession(
        thermal: ThermalStatusProvider = AuxFakeThermalStatusProvider(),
        counters: TelemetryCounters = com.contextsolutions.localagent.telemetry.NoOpTelemetryCounters,
        warmUpResult: Boolean = true,
    ): Triple<AuxModelSession, FakeAuxEngine, ThermalStatusProvider> {
        val fake = FakeAuxEngine(warmUpResult = warmUpResult)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val session = AuxModelSession(
            name = "test",
            warmUpDelegate = { fake.warmUp() },
            unloadDelegate = { fake.unload() },
            thermalStatusProvider = thermal,
            counters = counters,
            counterNames = CLASSIFIER_COUNTER_NAMES,
        )
        session.idleTimeout = IDLE_TIMEOUT
        session.idleTimeoutAfterWarmUp = POST_WARMUP_IDLE_TIMEOUT
        session.ioDispatcher = dispatcher
        session.scope = CoroutineScope(SupervisorJob() + dispatcher)
        return Triple(session, fake, thermal)
    }

    private companion object {
        val IDLE_TIMEOUT = 1.minutes
        val POST_WARMUP_IDLE_TIMEOUT = 15.seconds

        /**
         * Use the classifier counter names for the test fixture so the
         * assertions read like production code. The session is generic
         * over which set is in use — same logic, just different strings.
         */
        val CLASSIFIER_COUNTER_NAMES = AuxModelSession.CounterNames(
            warmupLoadedTotal = CounterNames.CLASSIFIER_WARMUP_LOADED_TOTAL,
            warmupAlreadyLoadedTotal = CounterNames.CLASSIFIER_WARMUP_ALREADY_LOADED_TOTAL,
            warmupSkippedThermalTotal = CounterNames.CLASSIFIER_WARMUP_SKIPPED_THERMAL_TOTAL,
            warmupFailedTotal = CounterNames.CLASSIFIER_WARMUP_FAILED_TOTAL,
            unloadedIdleTotal = CounterNames.CLASSIFIER_UNLOADED_IDLE_TOTAL,
            unloadedTrimMemoryTotal = CounterNames.CLASSIFIER_UNLOADED_TRIM_MEMORY_TOTAL,
            unloadedWatchdogTotal = CounterNames.CLASSIFIER_UNLOADED_WATCHDOG_TOTAL,
        )
    }
}

private class FakeAuxEngine(
    private val warmUpResult: Boolean = true,
) {
    val warmUpCount = AtomicInteger(0)
    val unloadCount = AtomicInteger(0)
    val useCount = AtomicInteger(0)

    fun warmUp(): Boolean {
        warmUpCount.incrementAndGet()
        return warmUpResult
    }

    fun unload() {
        unloadCount.incrementAndGet()
    }

    fun markUsed() {
        useCount.incrementAndGet()
    }
}

private class AuxRecordingCounters : TelemetryCounters {
    private val counters = ConcurrentHashMap<String, AtomicLong>()

    override fun increment(name: String, by: Long) {
        counters.computeIfAbsent(name) { AtomicLong(0) }.addAndGet(by)
    }

    override fun observeLatency(metric: String, durationMs: Long) = Unit

    fun value(name: String): Long = counters[name]?.get() ?: 0L
}

private class AuxFakeThermalStatusProvider(
    initial: ThermalStatus = ThermalStatus.NONE,
) : ThermalStatusProvider {
    private val state = MutableStateFlow(initial)
    override fun current(): ThermalStatus = state.value
    override fun statusFlow() = state.asStateFlow()
}
