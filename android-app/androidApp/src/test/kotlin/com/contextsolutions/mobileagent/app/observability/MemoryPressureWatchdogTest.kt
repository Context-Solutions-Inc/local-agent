package com.contextsolutions.mobileagent.app.observability

import com.contextsolutions.mobileagent.app.service.AuxModelLifecycleCoordinator
import com.contextsolutions.mobileagent.app.service.InferenceSessionManager
import com.contextsolutions.mobileagent.inference.SessionState
import com.contextsolutions.mobileagent.app.service.UnloadReason
import com.contextsolutions.mobileagent.inference.Accelerator
import com.contextsolutions.mobileagent.inference.MemoryHeadroomProvider
import com.contextsolutions.mobileagent.inference.SystemMemoryThresholds
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * PR #16 — drives [MemoryPressureWatchdog] under a TestDispatcher so we can
 * step through the polling loop deterministically. The session manager and
 * aux coordinator are mocked because the watchdog's only contract with
 * either is "subscribe to state, call forceUnload on threshold breach" —
 * everything else is in their own tests.
 *
 * Invariants under test:
 *  - No `forceUnload` while session state is not Loaded, even when memory
 *    is low.
 *  - The first sub-threshold reading after state turns Loaded triggers
 *    `forceUnload(UnloadReason.LowMemory)` on BOTH the session manager and
 *    the aux coordinator.
 *  - The polling loop stops after firing; the next Loaded transition
 *    re-arms it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoryPressureWatchdogTest {

    private val sessionManager: InferenceSessionManager = mockk(relaxed = true)
    private val auxCoordinator: AuxModelLifecycleCoordinator = mockk(relaxed = true)
    private val provider = FakeMemoryHeadroomProvider()
    private val state = MutableStateFlow<SessionState>(SessionState.Unloaded)

    private lateinit var watchdog: MemoryPressureWatchdog

    @After
    fun tearDown() {
        if (::watchdog.isInitialized) watchdog.stop()
    }

    @Test
    fun `no unload while state is Unloaded even at zero free memory`() = runTest {
        provider.value = 0L
        every { sessionManager.state } returns state.asStateFlow()
        every { sessionManager.forceUnload(any()) } just Runs
        every { auxCoordinator.forceUnload(any()) } just Runs

        watchdog = buildWatchdog()
        watchdog.start()
        advanceTimeBy(60_000L)
        runCurrent()

        verify(exactly = 0) { sessionManager.forceUnload(UnloadReason.LowMemory) }
        verify(exactly = 0) { auxCoordinator.forceUnload(UnloadReason.LowMemory) }
    }

    @Test
    fun `unloads on first sub-threshold reading after Loaded transition`() = runTest {
        provider.value = 2_000L * 1024 * 1024 // 2 GB — above the 800 MB watchdog floor
        every { sessionManager.state } returns state.asStateFlow()
        every { sessionManager.forceUnload(any()) } just Runs
        every { auxCoordinator.forceUnload(any()) } just Runs

        watchdog = buildWatchdog()
        watchdog.start()
        advanceUntilIdle()

        state.value = SessionState.Loaded(Accelerator.CPU)
        advanceTimeBy(5L)
        runCurrent()

        // Above-threshold reading: nothing fires yet.
        verify(exactly = 0) { sessionManager.forceUnload(UnloadReason.LowMemory) }

        // Pressure builds. The next poll cycle picks it up.
        provider.value = 500L * 1024 * 1024 // 500 MB — well below 800 MB floor
        advanceTimeBy(20L)
        advanceUntilIdle()

        verify(exactly = 1) { sessionManager.forceUnload(UnloadReason.LowMemory) }
        verify(exactly = 1) { auxCoordinator.forceUnload(UnloadReason.LowMemory) }
    }

    @Test
    fun `re-arms after a subsequent Loaded transition`() = runTest {
        provider.value = 500L * 1024 * 1024 // Start low.
        every { sessionManager.state } returns state.asStateFlow()
        every { sessionManager.forceUnload(any()) } just Runs
        every { auxCoordinator.forceUnload(any()) } just Runs

        watchdog = buildWatchdog()
        watchdog.start()
        advanceUntilIdle()

        // First load → fires.
        state.value = SessionState.Loaded(Accelerator.CPU)
        advanceTimeBy(20L)
        advanceUntilIdle()
        verify(exactly = 1) { sessionManager.forceUnload(UnloadReason.LowMemory) }

        // Session goes back to Unloaded (simulating the real manager's
        // deferred unload completing).
        state.value = SessionState.Unloaded
        advanceUntilIdle()

        // Second load → the watchdog re-enters its inner block via
        // collectLatest and fires again on the still-low reading.
        state.value = SessionState.Loaded(Accelerator.CPU)
        advanceTimeBy(20L)
        advanceUntilIdle()
        verify(exactly = 2) { sessionManager.forceUnload(UnloadReason.LowMemory) }
    }

    private fun kotlinx.coroutines.test.TestScope.buildWatchdog(): MemoryPressureWatchdog {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return MemoryPressureWatchdog(
            sessionManager = sessionManager,
            auxModelCoordinator = auxCoordinator,
            provider = provider,
            thresholds = SystemMemoryThresholds.DEFAULT,
        ).apply {
            pollIntervalMs = 10L
            scope = CoroutineScope(SupervisorJob() + dispatcher)
        }
    }
}

private class FakeMemoryHeadroomProvider : MemoryHeadroomProvider {
    @Volatile var value: Long = Long.MAX_VALUE
    override fun availableBytes(): Long = value
}
