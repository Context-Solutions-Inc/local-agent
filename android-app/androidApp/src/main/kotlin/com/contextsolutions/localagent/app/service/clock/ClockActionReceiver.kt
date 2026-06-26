package com.contextsolutions.localagent.app.service.clock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.contextsolutions.localagent.clock.ClockService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Handles the notification action buttons that the firing paths post:
 *
 *  - **Snooze** (alarm) — stop the currently ringing service for 10 minutes; the
 *    service schedules a one-shot AlarmManager fire on its own that re-starts
 *    itself when the snooze window elapses.
 *  - **Off for today** (alarm) — stop ringing now; for recurring alarms, leave the
 *    next-occurrence schedule in place (today is skipped, tomorrow / next weekday
 *    rings). One-shot alarms are cancelled outright.
 *  - **Stop** (timer, PR #22) — silence the ringing [TimerFiringService].
 *  - **+1 minute** (timer, PR #22) — silence the ring and arm a fresh 60 s timer.
 *
 * Timer actions forward to [TimerFiringService] (which owns the ring + label),
 * mirroring how the alarm snooze forwards to [AlarmFiringService].
 */
class ClockActionReceiver : BroadcastReceiver(), KoinComponent {

    private val clockService: ClockService by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        when (intent.action) {
            ACTION_SNOOZE -> snooze(context, id)
            ACTION_OFF_FOR_TODAY -> offForToday(context, id)
            ACTION_STOP_TIMER -> forwardToTimer(context, id, TimerFiringService.ACTION_STOP)
            ACTION_ADD_MINUTE -> forwardToTimer(context, id, TimerFiringService.ACTION_ADD_MINUTE)
        }
    }

    private fun snooze(context: Context, id: String) {
        val service = Intent(context, AlarmFiringService::class.java).apply {
            action = AlarmFiringService.ACTION_SNOOZE
            putExtra(AlarmFiringService.EXTRA_ALARM_ID, id)
        }
        context.startService(service)
    }

    private fun offForToday(context: Context, id: String) {
        clockService.skipAlarmForToday(id)
        // skipAlarmForToday already calls AlarmScheduler.stopFiringAlarm.
    }

    private fun forwardToTimer(context: Context, id: String, serviceAction: String) {
        val service = Intent(context, TimerFiringService::class.java).apply {
            action = serviceAction
            putExtra(TimerFiringService.EXTRA_TIMER_ID, id)
        }
        context.startService(service)
    }

    companion object {
        const val ACTION_SNOOZE = "com.contextsolutions.localagent.action.SNOOZE"
        const val ACTION_OFF_FOR_TODAY = "com.contextsolutions.localagent.action.OFF_FOR_TODAY"
        const val ACTION_STOP_TIMER = "com.contextsolutions.localagent.action.STOP_TIMER"
        const val ACTION_ADD_MINUTE = "com.contextsolutions.localagent.action.ADD_MINUTE"
        const val EXTRA_ID = "id"
    }
}
