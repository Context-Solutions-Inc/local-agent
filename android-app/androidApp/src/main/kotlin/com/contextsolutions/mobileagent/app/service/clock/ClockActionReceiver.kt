package com.contextsolutions.mobileagent.app.service.clock

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.contextsolutions.mobileagent.clock.ClockService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Handles the notification action buttons that the alarm-firing path posts:
 *
 *  - **Snooze** — stop the currently ringing service for 10 minutes; the
 *    service schedules a one-shot AlarmManager fire on its own that
 *    re-starts itself when the snooze window elapses.
 *  - **Off for today** — stop ringing now; for recurring alarms, leave the
 *    next-occurrence schedule in place (today is skipped, tomorrow / next
 *    weekday rings). One-shot alarms are cancelled outright.
 *  - **Dismiss timer** — cancel the timer-fired notification (timers don't
 *    have a repeating service, so this just clears the notification).
 */
class ClockActionReceiver : BroadcastReceiver(), KoinComponent {

    private val clockService: ClockService by inject()
    private val clockNotifications: ClockNotifications by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        when (intent.action) {
            ACTION_SNOOZE -> snooze(context, id)
            ACTION_OFF_FOR_TODAY -> offForToday(context, id)
            ACTION_DISMISS_TIMER -> dismissTimer(context, id)
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

    private fun dismissTimer(context: Context, id: String) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr.cancel(clockNotifications.timerNotificationId(id))
    }

    companion object {
        const val ACTION_SNOOZE = "com.contextsolutions.mobileagent.action.SNOOZE"
        const val ACTION_OFF_FOR_TODAY = "com.contextsolutions.mobileagent.action.OFF_FOR_TODAY"
        const val ACTION_DISMISS_TIMER = "com.contextsolutions.mobileagent.action.DISMISS_TIMER"
        const val EXTRA_ID = "id"
    }
}
