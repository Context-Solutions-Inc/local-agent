package com.contextsolutions.localagent.clock

/**
 * Platform-side OS scheduling for clock events. Android implementation wraps
 * `AlarmManager`; tests substitute a fake. Pure platform side-effects — the
 * repository stores user intent, the scheduler arms the OS to fire on it.
 *
 * Scheduling is idempotent at the `id` granularity: arming the same id twice
 * cancels the prior arm. Cancellation is safe to call for ids that were
 * never armed.
 */
interface AlarmScheduler {

    /** Schedule a timer to fire at the given absolute wall-clock instant. */
    fun scheduleTimer(timerId: String, fireAtEpochMs: Long)

    /** Cancel a timer's pending fire (no-op if not armed). */
    fun cancelTimer(timerId: String)

    /** Schedule an alarm to fire at the given absolute wall-clock instant. */
    fun scheduleAlarm(alarmId: String, fireAtEpochMs: Long)

    /** Cancel an alarm's pending fire and any in-progress firing service. */
    fun cancelAlarm(alarmId: String)

    /**
     * Stop an alarm that is currently ringing (foreground firing service
     * running) WITHOUT cancelling its recurrence — used by the "Off for
     * today" notification action. Implementations should ensure the next
     * recurrence is still re-armed.
     */
    fun stopFiringAlarm(alarmId: String)
}
