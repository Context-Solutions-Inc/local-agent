package com.contextsolutions.localagent.clock

import com.contextsolutions.localagent.notification.AppNotification
import com.contextsolutions.localagent.notification.NotificationKind
import com.contextsolutions.localagent.notification.NotificationPresenter
import com.contextsolutions.localagent.platform.AgentClock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Desktop [AlarmScheduler] (docs/DESKTOP_PORT_PLAN.md, Phase 7) — the coroutine
 * `delay`-until-instant registry that replaces Android's `AlarmManager`.
 *
 * For each armed timer/alarm a coroutine sleeps until the absolute wall-clock
 * instant, then drives the firing path the way Android's `ClockEventReceiver`
 * does: it reads the entry, calls [ClockService.onTimerFired] / [onAlarmFired]
 * (which deletes one-shots and **re-arms recurring alarms**, re-entering
 * [scheduleAlarm] for the next occurrence), and surfaces a notification through
 * the [NotificationPresenter] seam.
 *
 * Design notes:
 *  - **Idempotent at id granularity** (the interface contract): arming an id
 *    cancels its prior coroutine first. Cancellation of an unknown id is a no-op.
 *  - **No own persistence.** The job registry is in-memory; the durable record
 *    of "what to fire" lives in [ClockRepository]. On app start, empty registry
 *    + [ClockService.rearmAll] re-creates the coroutines — the desktop analogue
 *    of Android's boot-receiver re-arm. (The app/tray increment calls rearmAll.)
 *  - **Drift-tolerant wait.** The delay is chunked ([WAIT_CHUNK_MS]) and the
 *    remaining time is recomputed against [AgentClock] each chunk, so a laptop
 *    sleeping through a fire instant still fires promptly on wake (a single
 *    multi-hour `delay` would over-sleep by the suspended duration).
 *  - **`clockServiceProvider` is lazy** to break the ClockService↔scheduler DI
 *    cycle: the scheduler is constructed first; `ClockService` is resolved on
 *    the first fire/re-arm, by which point the graph is fully built.
 *  - **Ringing alarms** are represented by an `ongoing` notification tracked in
 *    [ringingAlarms]; [stopFiringAlarm] dismisses it WITHOUT touching the
 *    recurrence (the next occurrence is already re-armed by `onAlarmFired`, and
 *    "Off for today" goes through `ClockService.skipAlarmForToday`). The looping
 *    sound + snooze/off actions of the Android firing service are a richer
 *    tray-backed presenter's job (a later Phase-7 increment).
 */
class DesktopAlarmScheduler(
    private val clockServiceProvider: () -> ClockService,
    private val presenter: NotificationPresenter,
    private val clock: AgentClock = AgentClock(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val logger: (String) -> Unit = {},
) : AlarmScheduler {

    private val timerJobs = ConcurrentHashMap<String, Job>()
    private val alarmJobs = ConcurrentHashMap<String, Job>()
    private val ringingAlarms = ConcurrentHashMap.newKeySet<String>()

    override fun scheduleTimer(timerId: String, fireAtEpochMs: Long) {
        timerJobs.remove(timerId)?.cancel()
        logger("arm timer $timerId at $fireAtEpochMs")
        timerJobs[timerId] = scope.launch {
            waitUntil(fireAtEpochMs)
            timerJobs.remove(timerId)
            val service = clockServiceProvider()
            val label = service.timersSnapshot().firstOrNull { it.id == timerId }?.label
            service.onTimerFired(timerId) // timers are always one-shot
            presenter.present(
                AppNotification(
                    id = notificationId(TIMER_PREFIX, timerId),
                    title = "Timer finished",
                    body = label ?: "Your timer is up.",
                    kind = NotificationKind.TIMER,
                ),
            )
        }
    }

    override fun cancelTimer(timerId: String) {
        timerJobs.remove(timerId)?.cancel()
    }

    override fun scheduleAlarm(alarmId: String, fireAtEpochMs: Long) {
        alarmJobs.remove(alarmId)?.cancel()
        logger("arm alarm $alarmId at $fireAtEpochMs")
        alarmJobs[alarmId] = scope.launch {
            waitUntil(fireAtEpochMs)
            alarmJobs.remove(alarmId)
            val service = clockServiceProvider()
            val label = service.alarmsSnapshot().firstOrNull { it.id == alarmId }?.label
            // Re-arms recurring (re-enters scheduleAlarm for the next occurrence)
            // or deletes a one-shot row — exactly the Android receiver's contract.
            service.onAlarmFired(alarmId)
            ringingAlarms.add(alarmId)
            presenter.present(
                AppNotification(
                    id = notificationId(ALARM_PREFIX, alarmId),
                    title = "Alarm",
                    body = label ?: "Alarm ringing.",
                    kind = NotificationKind.ALARM,
                    ongoing = true,
                ),
            )
        }
    }

    override fun cancelAlarm(alarmId: String) {
        alarmJobs.remove(alarmId)?.cancel()
        stopFiringAlarm(alarmId)
    }

    override fun stopFiringAlarm(alarmId: String) {
        if (ringingAlarms.remove(alarmId)) {
            presenter.dismiss(notificationId(ALARM_PREFIX, alarmId))
        }
    }

    /**
     * Suspend until [fireAtEpochMs], recomputing the remaining time against the
     * wall clock each chunk so the fire survives clock changes / sleep. Returns
     * immediately if the instant is already in the past.
     */
    private suspend fun waitUntil(fireAtEpochMs: Long) {
        while (true) {
            val remaining = fireAtEpochMs - clock.nowEpochMs()
            if (remaining <= 0L) return
            delay(remaining.coerceAtMost(WAIT_CHUNK_MS))
        }
    }

    private fun notificationId(prefix: String, id: String): String = "$prefix$id"

    private companion object {
        const val WAIT_CHUNK_MS = 60_000L
        const val TIMER_PREFIX = "timer:"
        const val ALARM_PREFIX = "alarm:"
    }
}
