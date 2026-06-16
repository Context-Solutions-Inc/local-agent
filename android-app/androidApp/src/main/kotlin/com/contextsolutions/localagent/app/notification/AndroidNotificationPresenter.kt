package com.contextsolutions.localagent.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.contextsolutions.localagent.app.R
import com.contextsolutions.localagent.notification.AppNotification
import com.contextsolutions.localagent.notification.NotificationKind
import com.contextsolutions.localagent.notification.NotificationPresenter
import kotlin.math.abs

/**
 * Android binding of the commonMain [NotificationPresenter] seam (PR #85). Only the
 * mobile job-completion path uses it today ([NotificationKind.JOB]); the clock
 * subsystem keeps its richer
 * [com.contextsolutions.localagent.app.service.clock.ClockNotifications] for now.
 *
 * Job notifications post on a dedicated `job_runs` channel at IMPORTANCE_DEFAULT (so
 * they alert, unlike the silent download/inference channels). Re-presenting the same
 * [AppNotification.id] (`job_run:<jobId>`) replaces that job's earlier notification.
 * Tapping opens the app (the user lands on chat → Jobs).
 */
class AndroidNotificationPresenter(
    private val context: Context,
) : NotificationPresenter {

    private val manager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    override fun present(notification: AppNotification) {
        ensureJobChannel()
        val built = NotificationCompat.Builder(context, CHANNEL_JOB_RUNS)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.body))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(!notification.ongoing)
            .setOngoing(notification.ongoing)
            .setContentIntent(launchAppPendingIntent())
            .build()
        manager.notify(notificationId(notification.id), built)
    }

    override fun dismiss(id: String) {
        manager.cancel(notificationId(id))
    }

    private fun notificationId(id: String): Int =
        JOB_NOTIFICATION_ID_BASE + (abs(id.hashCode()) % 100_000)

    private fun ensureJobChannel() {
        if (manager.getNotificationChannel(CHANNEL_JOB_RUNS) == null) {
            val channel = NotificationChannel(
                CHANNEL_JOB_RUNS,
                context.getString(R.string.job_run_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.job_run_channel_desc)
            }
            manager.createNotificationChannel(channel)
        }
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

    private companion object {
        const val CHANNEL_JOB_RUNS = "job_runs"
        // Distinct from clock (2_000_000 / 3_000_000), download (2001), inference (1001).
        const val JOB_NOTIFICATION_ID_BASE = 4_000_000
    }
}
