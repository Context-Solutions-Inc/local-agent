package com.contextsolutions.localagent.app.service.clock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.contextsolutions.localagent.app.R
import kotlin.math.abs

/**
 * Centralised notification builder for clock events. Two channels:
 *
 *  - **alarm_fired** (IMPORTANCE_HIGH, alarm-stream sound, bypass-DND on
 *    devices that allow it) — used by [AlarmFiringService] while an alarm
 *    is actively ringing.
 *  - **timer_fired** (IMPORTANCE_HIGH, default notification sound) — single
 *    chime when a countdown timer elapses.
 *
 * Sound for the alarm channel is set on the *channel*, not the
 * notification, because Android 8+ ignores per-notification sound when a
 * channel is in use. AlarmFiringService also drives a [MediaPlayer] for
 * the looping sound + a [Vibrator] pattern — channel sound alone is
 * single-shot.
 */
class ClockNotifications(
    private val context: Context,
) {

    private val manager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    init {
        ensureChannels()
    }

    /** Notification id for the foreground firing notification of a given alarm. */
    fun alarmFiringNotificationId(id: String): Int =
        ALARM_NOTIFICATION_ID_BASE + (abs(id.hashCode()) % 100_000)

    /** Notification id for a fired timer. */
    fun timerNotificationId(id: String): Int =
        TIMER_NOTIFICATION_ID_BASE + (abs(id.hashCode()) % 100_000)

    /**
     * Build the alarm-firing notification. Re-built every 10 s by the
     * service (re-posting an updated notification re-plays the channel
     * sound on some OEM skins — belt-and-suspenders with the MediaPlayer
     * loop). Two action buttons: Snooze, Off-for-today.
     */
    fun buildAlarmFiringNotification(
        alarmId: String,
        label: String?,
    ): android.app.Notification {
        val title = context.getString(R.string.clock_alarm_firing_title)
        val text = label?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.clock_alarm_firing_text_default)

        val snoozeAction = NotificationCompat.Action(
            0,
            context.getString(R.string.clock_action_snooze),
            actionPendingIntent(ClockActionReceiver.ACTION_SNOOZE, alarmId),
        )
        val offAction = NotificationCompat.Action(
            0,
            context.getString(R.string.clock_action_off_for_today),
            actionPendingIntent(ClockActionReceiver.ACTION_OFF_FOR_TODAY, alarmId),
        )

        return NotificationCompat.Builder(context, CHANNEL_ALARM_FIRED)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false) // re-alert on every re-post
            .setContentIntent(launchAppPendingIntent())
            .addAction(snoozeAction)
            .addAction(offAction)
            .build()
    }

    fun postTimerFiredNotification(timerId: String, label: String?) {
        val title = context.getString(R.string.clock_timer_fired_title)
        val text = label?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.clock_timer_fired_text_default)
        val dismissAction = NotificationCompat.Action(
            0,
            context.getString(R.string.clock_action_dismiss),
            actionPendingIntent(ClockActionReceiver.ACTION_DISMISS_TIMER, timerId),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_TIMER_FIRED)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(launchAppPendingIntent())
            .addAction(dismissAction)
            .build()
        manager.notify(timerNotificationId(timerId), notification)
    }

    private fun ensureChannels() {
        if (manager.getNotificationChannel(CHANNEL_ALARM_FIRED) == null) {
            val alarmSound: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val channel = NotificationChannel(
                CHANNEL_ALARM_FIRED,
                context.getString(R.string.clock_alarm_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.clock_alarm_channel_desc)
                setSound(alarmSound, attrs)
                enableVibration(true)
                setBypassDnd(true)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
        if (manager.getNotificationChannel(CHANNEL_TIMER_FIRED) == null) {
            val channel = NotificationChannel(
                CHANNEL_TIMER_FIRED,
                context.getString(R.string.clock_timer_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.clock_timer_channel_desc)
                enableVibration(true)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun actionPendingIntent(action: String, id: String): PendingIntent {
        val intent = Intent(context, ClockActionReceiver::class.java).apply {
            this.action = action
            putExtra(ClockActionReceiver.EXTRA_ID, id)
            data = Uri.parse("localagent://action/$action/$id")
        }
        return PendingIntent.getBroadcast(
            context,
            (action + id).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun launchAppPendingIntent(): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent()
        return PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_ALARM_FIRED = "clock_alarm_fired"
        const val CHANNEL_TIMER_FIRED = "clock_timer_fired"
        // Notification id namespacing — keeps clock IDs distinct from
        // InferenceForegroundService (1001) and ModelDownloadWorker.
        const val ALARM_NOTIFICATION_ID_BASE = 2_000_000
        const val TIMER_NOTIFICATION_ID_BASE = 3_000_000
    }
}
