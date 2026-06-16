package com.contextsolutions.localagent.app.service.clock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.contextsolutions.localagent.clock.AlarmScheduler

/**
 * [AlarmScheduler] backed by Android's [AlarmManager]. Two firing paths:
 *
 *  - **Timers** use [AlarmManager.setExactAndAllowWhileIdle] — fires through
 *    Doze, one-shot, no system-UI affordance. Lowest-friction primitive that
 *    actually fires on time on modern Android.
 *  - **Alarms** use [AlarmManager.setAlarmClock] — surfaces the next alarm
 *    in the system status bar and is the most reliable scheduling primitive
 *    Android offers (intended for actual alarm-clock apps). Requires
 *    `USE_EXACT_ALARM` or `SCHEDULE_EXACT_ALARM`; we declare the former
 *    because this *is* alarm-clock functionality and `USE_EXACT_ALARM` is
 *    install-granted (no runtime prompt).
 *
 * The PendingIntent flag combo (`FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE`) means
 * re-scheduling the same id replaces the prior arm without us calling
 * cancel() first — Android collapses by `(requestCode, action, data)`.
 *
 * `requestCode` derivation uses a stable hash of the id so cancellation
 * (with a matching PendingIntent) reliably targets the same scheduled
 * arming. Collisions would be cosmetic (two distinct ids sharing one
 * PendingIntent slot) but extremely unlikely with UUID-based ids.
 *
 * Note: [stopFiringAlarm] does NOT cancel the recurrence schedule — it only
 * stops the in-progress ringing service. The next-fire math handles the
 * skip via [com.contextsolutions.localagent.clock.AlarmEntry.skipNextDateIso].
 */
class AndroidAlarmScheduler(
    private val context: Context,
) : AlarmScheduler {

    private val manager: AlarmManager = context.getSystemService(AlarmManager::class.java)

    override fun scheduleTimer(timerId: String, fireAtEpochMs: Long) {
        val pi = timerPendingIntent(timerId, mutable = false)
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtEpochMs, pi)
    }

    override fun cancelTimer(timerId: String) {
        manager.cancel(timerPendingIntent(timerId, mutable = false))
    }

    override fun scheduleAlarm(alarmId: String, fireAtEpochMs: Long) {
        val firingPi = alarmFiringPendingIntent(alarmId)
        // showIntent: tapping the "next alarm" status-bar pill should open
        // the app to the Chat surface. MainActivity is the launch activity.
        val showPi = launchAppPendingIntent()
        val info = AlarmManager.AlarmClockInfo(fireAtEpochMs, showPi)
        manager.setAlarmClock(info, firingPi)
    }

    override fun cancelAlarm(alarmId: String) {
        manager.cancel(alarmFiringPendingIntent(alarmId))
        // Also stop a service that might already be ringing for this id.
        stopFiringAlarm(alarmId)
    }

    override fun stopFiringAlarm(alarmId: String) {
        val intent = Intent(context, AlarmFiringService::class.java).apply {
            action = AlarmFiringService.ACTION_STOP
            putExtra(AlarmFiringService.EXTRA_ALARM_ID, alarmId)
        }
        context.startService(intent)
    }

    private fun timerPendingIntent(timerId: String, mutable: Boolean): PendingIntent {
        val intent = Intent(context, ClockEventReceiver::class.java).apply {
            action = ClockEventReceiver.ACTION_TIMER_FIRE
            putExtra(ClockEventReceiver.EXTRA_ID, timerId)
            // Setting data ensures Android treats two different timer ids as
            // two different PendingIntents even if requestCode collides.
            data = android.net.Uri.parse("localagent://timer/$timerId")
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode(timerId), intent, flags)
    }

    private fun alarmFiringPendingIntent(alarmId: String): PendingIntent {
        val intent = Intent(context, ClockEventReceiver::class.java).apply {
            action = ClockEventReceiver.ACTION_ALARM_FIRE
            putExtra(ClockEventReceiver.EXTRA_ID, alarmId)
            data = android.net.Uri.parse("localagent://alarm/$alarmId")
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(alarmId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun launchAppPendingIntent(): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent()
        return PendingIntent.getActivity(
            context,
            LAUNCH_REQUEST_CODE,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Stable positive request code from an id. AlarmManager keys PendingIntents
     * by `(requestCode, filterEquals(intent))`; we also set distinct data Uris
     * above so two ids that happen to hash to the same code still don't
     * collapse.
     */
    private fun requestCode(id: String): Int = id.hashCode() and 0x7FFFFFFF

    private companion object {
        // High-bit-cleared so the int stays positive (some Android versions
        // reject negative requestCodes inside specific PendingIntent paths).
        const val LAUNCH_REQUEST_CODE = 0x2E57C0DE
    }
}
