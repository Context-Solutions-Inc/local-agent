package com.contextsolutions.localagent.app.ui.clock

import com.contextsolutions.localagent.clock.AlarmEntry
import com.contextsolutions.localagent.clock.ClockRepository
import com.contextsolutions.localagent.clock.ClockService
import com.contextsolutions.localagent.clock.TimerEntry
import com.contextsolutions.localagent.ui.clock.ClockViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * PR #4 — alarms must always render earliest-first by time, and that order must
 * be stable when an alarm is toggled on/off (the repositories append the
 * upserted alarm to the end, which used to reorder the list).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClockViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val service: ClockService = mockk(relaxed = true)

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun alarm(id: String, hour: Int, minute: Int, enabled: Boolean = true) =
        AlarmEntry(id = id, label = null, hour = hour, minute = minute, enabled = enabled, createdAtEpochMs = 0L)

    @Test
    fun alarms_sorted_by_time_and_stable_across_toggle() = runTest {
        // Repository emits an unsorted list (insertion order).
        val flow = MutableStateFlow(listOf(alarm("a", 22, 0), alarm("b", 7, 30), alarm("c", 8, 30)))
        val repo = mockk<ClockRepository>(relaxed = true)
        every { repo.alarms() } returns flow
        every { repo.snapshotAlarms() } returns flow.value
        every { repo.timers() } returns MutableStateFlow(emptyList<TimerEntry>())
        every { repo.snapshotTimers() } returns emptyList()

        val vm = ClockViewModel(service, repo)
        backgroundScope.launch { vm.alarms.collect {} } // start the WhileSubscribed upstream
        advanceUntilIdle()

        assertEquals(listOf("b", "c", "a"), vm.alarms.value.map { it.id })

        // Simulate a toggle: the repo appends the toggled alarm to the end.
        flow.value = listOf(alarm("b", 7, 30), alarm("c", 8, 30), alarm("a", 22, 0, enabled = false))
        advanceUntilIdle()

        // Order is unchanged — still earliest-first by time.
        assertEquals(listOf("b", "c", "a"), vm.alarms.value.map { it.id })
    }
}
