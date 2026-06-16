package com.contextsolutions.localagent.app.observability

import com.contextsolutions.localagent.inference.MemoryHeadroomProvider
import com.contextsolutions.localagent.inference.MemoryStatus
import com.contextsolutions.localagent.inference.SystemMemoryThresholds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PR #18 — drives [SystemMemoryMonitor] under a TestDispatcher so we can
 * step the polling loop deterministically and assert the published
 * [MemoryStatus] tracks the underlying [MemoryHeadroomProvider].
 *
 * Important: the monitor's polling loop is `while (isActive) { delay(...) }`
 * with no exit condition, so `advanceUntilIdle()` would loop forever
 * advancing virtual time. Each test instead uses bounded `advanceTimeBy`
 * + `runCurrent` calls and stops the monitor in @After.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SystemMemoryMonitorTest {

    private val provider = MonitorFakeMemoryHeadroomProvider()
    private lateinit var monitor: SystemMemoryMonitor

    @After
    fun tearDown() {
        if (::monitor.isInitialized) monitor.stop()
    }

    @Test
    fun `initial value reflects provider at construction time`() = runTest {
        provider.value = 500L * 1024 * 1024 // 500 MB
        monitor = buildMonitor()
        // No start() — initial value comes from the constructor's
        // `thresholds.classify(provider.availableBytes())` call.
        assertEquals(MemoryStatus.Red, monitor.status.value)
    }

    @Test
    fun `status transitions Green to Yellow to Red as available bytes drop`() = runTest {
        provider.value = 4L * 1024 * 1024 * 1024 // 4 GiB
        monitor = buildMonitor()
        monitor.start()
        runCurrent()
        assertEquals(MemoryStatus.Green, monitor.status.value)

        provider.value = 1_500L * 1024 * 1024 // 1.46 GiB — yellow band
        advanceTimeBy(20L)
        runCurrent()
        assertEquals(MemoryStatus.Yellow, monitor.status.value)

        provider.value = 500L * 1024 * 1024 // 500 MB — red band
        advanceTimeBy(20L)
        runCurrent()
        assertEquals(MemoryStatus.Red, monitor.status.value)
        // Stop INSIDE the test body. The polling loop is `while (isActive)
        // { delay() }` with no exit condition, so leaving it running would
        // leak pending delay tasks onto the shared test scheduler and
        // hang `runTest`'s exit-time `advanceUntilIdle`.
        monitor.stop()
    }

    @Test
    fun `status recovers from Red to Green when pressure clears`() = runTest {
        provider.value = 100L * 1024 * 1024
        monitor = buildMonitor()
        monitor.start()
        runCurrent()
        assertEquals(MemoryStatus.Red, monitor.status.value)

        provider.value = 5L * 1024 * 1024 * 1024
        advanceTimeBy(20L)
        runCurrent()
        assertEquals(MemoryStatus.Green, monitor.status.value)
        monitor.stop()
    }

    @Test
    fun `start is idempotent — second start does not throw`() = runTest {
        provider.value = 5L * 1024 * 1024 * 1024
        monitor = buildMonitor()
        monitor.start()
        monitor.start()
        runCurrent()
        assertEquals(MemoryStatus.Green, monitor.status.value)
        monitor.stop()
        monitor.stop()
    }

    private fun kotlinx.coroutines.test.TestScope.buildMonitor(): SystemMemoryMonitor {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return SystemMemoryMonitor(
            provider = provider,
            thresholds = SystemMemoryThresholds.DEFAULT,
        ).apply {
            pollIntervalMs = 10L
            scope = CoroutineScope(SupervisorJob() + dispatcher)
        }
    }
}

private class MonitorFakeMemoryHeadroomProvider : MemoryHeadroomProvider {
    @Volatile var value: Long = Long.MAX_VALUE
    override fun availableBytes(): Long = value
}
