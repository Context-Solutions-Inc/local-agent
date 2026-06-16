package com.contextsolutions.localagent.clock

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Coordinates the repository + scheduler so callers (UI ViewModel, agent
 * tool handler, boot rearmer) don't have to keep them in sync themselves.
 *
 *  - **Create / update**: writes to the repo, arms the scheduler.
 *  - **Cancel**: cancels the scheduler arm, removes the row.
 *  - **Boot / app start**: [rearmAll] walks every persisted row and re-arms
 *    the OS. Safe to call multiple times.
 *  - **Alarm fired**: [onAlarmFired] is invoked by the firing path; for
 *    recurring alarms it computes the next occurrence and re-arms. For
 *    one-shot alarms it deletes the row.
 *  - **Timer fired**: [onTimerFired] deletes the row (timers are always
 *    one-shot).
 *  - **"Off for today"**: [skipAlarmForToday] sets the skip date so the
 *    next occurrence math jumps past the current local date.
 */
@OptIn(ExperimentalUuidApi::class)
class ClockService(
    private val repository: ClockRepository,
    private val scheduler: AlarmScheduler,
    private val clock: Clock = Clock.System,
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {

    // ---- Snapshots (read-through to repo) ----------------------------------

    /** Snapshot reads for callers that don't subscribe to the flows. */
    fun timersSnapshot(): List<TimerEntry> = repository.snapshotTimers()
    fun alarmsSnapshot(): List<AlarmEntry> = repository.snapshotAlarms()

    // ---- Timers ------------------------------------------------------------

    fun createTimer(durationMs: Long, label: String? = null): TimerEntry {
        require(durationMs > 0) { "duration must be positive" }
        val now = clock.now()
        val timer = TimerEntry(
            id = "timer-${Uuid.random()}",
            label = label?.takeIf { it.isNotBlank() },
            durationMs = durationMs,
            fireAtEpochMs = now.toEpochMilliseconds() + durationMs,
            createdAtEpochMs = now.toEpochMilliseconds(),
        )
        repository.upsertTimer(timer)
        scheduler.scheduleTimer(timer.id, timer.fireAtEpochMs)
        return timer
    }

    fun extendTimer(id: String, extraMs: Long): TimerEntry? {
        val existing = repository.snapshotTimers().firstOrNull { it.id == id } ?: return null
        val updated = existing.copy(fireAtEpochMs = existing.fireAtEpochMs + extraMs)
        repository.upsertTimer(updated)
        scheduler.scheduleTimer(updated.id, updated.fireAtEpochMs)
        return updated
    }

    fun cancelTimer(id: String) {
        scheduler.cancelTimer(id)
        repository.deleteTimer(id)
    }

    /** Called when the OS fires a scheduled timer — always one-shot. */
    fun onTimerFired(id: String) {
        repository.deleteTimer(id)
    }

    // ---- Alarms ------------------------------------------------------------

    fun createAlarm(
        hour: Int,
        minute: Int,
        recurringDays: Set<AlarmDay> = emptySet(),
        label: String? = null,
    ): AlarmEntry? {
        require(hour in 0..23) { "hour out of range" }
        require(minute in 0..59) { "minute out of range" }
        val alarm = AlarmEntry(
            id = "alarm-${Uuid.random()}",
            label = label?.takeIf { it.isNotBlank() },
            hour = hour,
            minute = minute,
            recurringDays = recurringDays,
            enabled = true,
            createdAtEpochMs = clock.now().toEpochMilliseconds(),
        )
        repository.upsertAlarm(alarm)
        return armAlarm(alarm)
    }

    fun updateAlarm(alarm: AlarmEntry): AlarmEntry? {
        val cleared = alarm.copy(skipNextDateIso = null)
        repository.upsertAlarm(cleared)
        return armAlarm(cleared)
    }

    fun cancelAlarm(id: String) {
        scheduler.cancelAlarm(id)
        repository.deleteAlarm(id)
    }

    fun setAlarmEnabled(id: String, enabled: Boolean): AlarmEntry? {
        val existing = repository.snapshotAlarms().firstOrNull { it.id == id } ?: return null
        val updated = existing.copy(enabled = enabled, skipNextDateIso = null)
        repository.upsertAlarm(updated)
        return if (enabled) armAlarm(updated) else {
            scheduler.cancelAlarm(id); updated
        }
    }

    /**
     * "Off for today" while a recurring alarm is ringing: stop the firing
     * service and set a skip date so the next next-fire calculation lands
     * on the following occurrence. One-shot alarms are deleted instead.
     */
    fun skipAlarmForToday(id: String): AlarmEntry? {
        val existing = repository.snapshotAlarms().firstOrNull { it.id == id } ?: return null
        scheduler.stopFiringAlarm(id)
        if (!existing.isRecurring) {
            cancelAlarm(id)
            return null
        }
        val todayIso = isoDate(clock.now(), timeZone())
        val updated = existing.copy(skipNextDateIso = todayIso)
        repository.upsertAlarm(updated)
        return armAlarm(updated)
    }

    /** Called when the OS fires a scheduled alarm. Re-arms recurring; deletes one-shot. */
    fun onAlarmFired(id: String) {
        val existing = repository.snapshotAlarms().firstOrNull { it.id == id } ?: return
        if (!existing.isRecurring) {
            repository.deleteAlarm(id)
            return
        }
        // Clear any pre-fire skipNextDate so subsequent occurrences resume.
        val cleared = existing.copy(skipNextDateIso = null)
        repository.upsertAlarm(cleared)
        armAlarm(cleared)
    }

    // ---- Boot / app start --------------------------------------------------

    fun rearmAll() {
        val now = clock.now()
        val tz = timeZone()
        for (timer in repository.snapshotTimers()) {
            if (timer.fireAtEpochMs <= now.toEpochMilliseconds()) {
                // Timer was supposed to fire while the device was off — clean it up.
                repository.deleteTimer(timer.id)
                continue
            }
            scheduler.scheduleTimer(timer.id, timer.fireAtEpochMs)
        }
        for (alarm in repository.snapshotAlarms()) {
            armAlarm(alarm, now = now, tz = tz)
        }
    }

    private fun armAlarm(
        alarm: AlarmEntry,
        now: Instant = clock.now(),
        tz: TimeZone = timeZone(),
    ): AlarmEntry {
        val next = NextFireCalculator.next(alarm, now, tz)
        if (next == null) {
            scheduler.cancelAlarm(alarm.id)
        } else {
            scheduler.scheduleAlarm(alarm.id, next)
        }
        return alarm
    }

    private fun isoDate(now: Instant, tz: TimeZone): String {
        val date: LocalDate = now.toLocalDateTime(tz).date
        return date.toString()
    }
}
