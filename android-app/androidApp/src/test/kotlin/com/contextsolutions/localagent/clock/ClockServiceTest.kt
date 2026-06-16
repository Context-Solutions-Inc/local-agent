package com.contextsolutions.localagent.clock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-memory smoke tests for [ClockService]. Uses an injected [FakeClock] +
 * [FakeScheduler] to assert that arms/cancels reach the OS in the right
 * order and that one-shot vs recurring alarms behave differently on fire.
 */
class ClockServiceTest {

    private val tz = TimeZone.of("UTC")
    private val clock = FakeClock(Instant.fromEpochMilliseconds(1_000_000L))
    private val repo = InMemoryClockRepository()
    private val scheduler = FakeScheduler()
    private val service = ClockService(
        repository = repo,
        scheduler = scheduler,
        clock = clock,
        timeZone = { tz },
    )

    @Test
    fun `createTimer arms scheduler and persists row`() {
        val t = service.createTimer(durationMs = 5_000)
        assertEquals(1, scheduler.timerArms.size)
        assertEquals(t.id, scheduler.timerArms.last().first)
        assertEquals(1_005_000L, scheduler.timerArms.last().second)
        assertEquals(1, repo.snapshotTimers().size)
    }

    @Test
    fun `cancelTimer removes the row and cancels arm`() {
        val t = service.createTimer(durationMs = 5_000)
        service.cancelTimer(t.id)
        assertTrue(repo.snapshotTimers().isEmpty())
        assertEquals(listOf(t.id), scheduler.timerCancels)
    }

    @Test
    fun `extendTimer pushes fireAt forward and re-arms`() {
        val t = service.createTimer(durationMs = 5_000)
        val before = t.fireAtEpochMs
        val updated = service.extendTimer(t.id, extraMs = 10_000)
        assertNotNull(updated)
        assertEquals(before + 10_000, updated!!.fireAtEpochMs)
        // Two arms: initial + extend.
        assertEquals(2, scheduler.timerArms.size)
    }

    @Test
    fun `createAlarm one-shot arms then deletes on fire`() {
        val alarm = service.createAlarm(hour = 8, minute = 0)
        assertNotNull(alarm)
        assertEquals(1, scheduler.alarmArms.size)
        service.onAlarmFired(alarm!!.id)
        assertTrue(repo.snapshotAlarms().isEmpty())
    }

    @Test
    fun `recurring alarm re-arms after fire`() {
        val alarm = service.createAlarm(
            hour = 8,
            minute = 0,
            recurringDays = setOf(AlarmDay.MONDAY, AlarmDay.TUESDAY),
        )!!
        val initialArmCount = scheduler.alarmArms.size
        service.onAlarmFired(alarm.id)
        // Row preserved + scheduler re-armed.
        assertEquals(1, repo.snapshotAlarms().size)
        assertTrue(scheduler.alarmArms.size > initialArmCount)
    }

    @Test
    fun `skipAlarmForToday on one-shot cancels outright`() {
        val alarm = service.createAlarm(hour = 8, minute = 0)!!
        val ret = service.skipAlarmForToday(alarm.id)
        assertNull(ret)
        assertTrue(repo.snapshotAlarms().isEmpty())
        assertTrue(scheduler.firingStops.contains(alarm.id))
    }

    @Test
    fun `setAlarmEnabled false cancels arm without deleting row`() {
        val alarm = service.createAlarm(hour = 8, minute = 0)!!
        val updated = service.setAlarmEnabled(alarm.id, enabled = false)
        assertNotNull(updated)
        assertFalse(updated!!.enabled)
        assertTrue(scheduler.alarmCancels.contains(alarm.id))
        assertEquals(1, repo.snapshotAlarms().size)
    }

    @Test
    fun `rearmAll drops expired timers and re-arms live ones`() {
        // Pre-populate the repo with an already-expired timer + a live one.
        val now = clock.now().toEpochMilliseconds()
        repo.upsertTimer(
            TimerEntry(
                id = "expired",
                label = null,
                durationMs = 1000,
                fireAtEpochMs = now - 1,
                createdAtEpochMs = now - 1000,
            ),
        )
        repo.upsertTimer(
            TimerEntry(
                id = "live",
                label = null,
                durationMs = 60_000,
                fireAtEpochMs = now + 60_000,
                createdAtEpochMs = now,
            ),
        )
        service.rearmAll()
        assertEquals(1, repo.snapshotTimers().size)
        assertEquals("live", repo.snapshotTimers().single().id)
        assertTrue(scheduler.timerArms.any { it.first == "live" })
    }
}

private class FakeClock(private var instant: Instant) : Clock {
    override fun now(): Instant = instant
    fun advance(ms: Long) { instant = Instant.fromEpochMilliseconds(instant.toEpochMilliseconds() + ms) }
}

private class FakeScheduler : AlarmScheduler {
    val timerArms = mutableListOf<Pair<String, Long>>()
    val timerCancels = mutableListOf<String>()
    val alarmArms = mutableListOf<Pair<String, Long>>()
    val alarmCancels = mutableListOf<String>()
    val firingStops = mutableListOf<String>()

    override fun scheduleTimer(timerId: String, fireAtEpochMs: Long) {
        timerArms += timerId to fireAtEpochMs
    }
    override fun cancelTimer(timerId: String) { timerCancels += timerId }
    override fun scheduleAlarm(alarmId: String, fireAtEpochMs: Long) {
        alarmArms += alarmId to fireAtEpochMs
    }
    override fun cancelAlarm(alarmId: String) { alarmCancels += alarmId }
    override fun stopFiringAlarm(alarmId: String) { firingStops += alarmId }
}

private class InMemoryClockRepository : ClockRepository {
    private val timersState = MutableStateFlow<List<TimerEntry>>(emptyList())
    private val alarmsState = MutableStateFlow<List<AlarmEntry>>(emptyList())
    override fun timers() = timersState.asStateFlow()
    override fun alarms() = alarmsState.asStateFlow()
    override fun snapshotTimers() = timersState.value
    override fun snapshotAlarms() = alarmsState.value
    override fun upsertTimer(timer: TimerEntry) {
        timersState.value = timersState.value.filterNot { it.id == timer.id } + timer
    }
    override fun deleteTimer(id: String) {
        timersState.value = timersState.value.filterNot { it.id == id }
    }
    override fun upsertAlarm(alarm: AlarmEntry) {
        alarmsState.value = alarmsState.value.filterNot { it.id == alarm.id } + alarm
    }
    override fun deleteAlarm(id: String) {
        alarmsState.value = alarmsState.value.filterNot { it.id == id }
    }
}
