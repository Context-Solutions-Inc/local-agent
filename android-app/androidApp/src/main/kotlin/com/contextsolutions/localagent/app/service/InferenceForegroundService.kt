package com.contextsolutions.localagent.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.contextsolutions.localagent.app.R

/**
 * Foreground service that wraps an active generation request so the OS keeps the
 * process alive while the user waits for tokens to stream in.
 *
 * Started when generation begins; stopped immediately when the generation Flow completes
 * or is cancelled. Lifecycle is controlled by the agent loop in M1+ — for M0 the spike
 * harness manages it directly to ensure inference benchmarks reflect real foreground
 * conditions on Pixel 7.
 */
class InferenceForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.fgs_inference_title))
            .setContentText(getString(R.string.fgs_inference_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.fgs_inference_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.fgs_inference_channel_desc)
                setShowBadge(false)
            }
            mgr.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "inference_fgs"
        private const val NOTIFICATION_ID = 1001
    }
}
