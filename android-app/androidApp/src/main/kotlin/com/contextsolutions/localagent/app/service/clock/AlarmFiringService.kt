package com.contextsolutions.localagent.app.service.clock

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Foreground service that drives a ringing alarm:
 *
 *  - Plays the default alarm sound on loop (USAGE_ALARM stream — bypasses
 *    silent mode appropriately for an alarm clock).
 *  - Vibrates on a repeating pattern.
 *  - Re-posts the firing notification every 10 s so the lock-screen pill
 *    stays visible and the channel sound re-asserts on OEM skins that
 *    suppress repeat sounds within a single notification id.
 *
 * Stops on user action:
 *
 *  - **Snooze** ([ACTION_SNOOZE]): stop now, schedule a one-shot self-restart
 *    via AlarmManager after [SNOOZE_MS].
 *  - **Off-for-today** is handled in [ClockActionReceiver] which calls into
 *    `ClockService.skipAlarmForToday` → `AlarmScheduler.stopFiringAlarm` →
 *    [ACTION_STOP] on this service.
 *
 * Multiple concurrent alarms: the service tracks fires by `alarmId` so two
 * alarms going off at the same instant don't collide. A separate
 * notification id per alarm keeps them distinct in the shade. Sound +
 * vibrator are shared (one alarm sound at a time is what users actually
 * want; the second alarm's notification still appears).
 *
 * Foreground type: `mediaPlayback` — we're playing real audio, and the
 * `specialUse` type already taken by `InferenceForegroundService` doesn't
 * fit alarm playback per the Android docs.
 */
class AlarmFiringService : Service(), KoinComponent {

    private val clockNotifications: ClockNotifications by inject()
    private val alarmScheduler: com.contextsolutions.localagent.clock.AlarmScheduler by inject()

    private val handler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    /** Active alarms currently firing: id → label. */
    private val firing = mutableMapOf<String, String?>()

    /** The id currently driving the foreground notification (the "primary" ring). */
    private var primaryId: String? = null

    private val repostTick = object : Runnable {
        override fun run() {
            val pid = primaryId ?: return
            val label = firing[pid]
            val notification = clockNotifications.buildAlarmFiringNotification(pid, label)
            try {
                val mgr = getSystemService(NotificationManager::class.java)
                mgr.notify(clockNotifications.alarmFiringNotificationId(pid), notification)
            } catch (t: Throwable) {
                Log.w(TAG, "notification re-post failed", t)
            }
            handler.postDelayed(this, REPOST_PERIOD_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val id = intent?.getStringExtra(EXTRA_ALARM_ID)

        when (action) {
            ACTION_START -> {
                if (id == null) return START_NOT_STICKY
                val label = intent.getStringExtra(EXTRA_ALARM_LABEL)
                startFiring(id, label)
            }
            ACTION_STOP -> {
                if (id != null) stopOne(id) else stopAll()
            }
            ACTION_SNOOZE -> {
                if (id != null) snoozeOne(id) else stopAll()
            }
            else -> Log.w(TAG, "unexpected action $action")
        }
        return START_NOT_STICKY
    }

    private fun startFiring(id: String, label: String?) {
        firing[id] = label
        if (primaryId == null) primaryId = id

        val pid = primaryId ?: id
        val notification = clockNotifications.buildAlarmFiringNotification(
            pid,
            firing[pid],
        )
        val notificationId = clockNotifications.alarmFiringNotificationId(pid)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(notificationId, notification)
        }
        startSoundAndVibrate()
        handler.removeCallbacks(repostTick)
        handler.postDelayed(repostTick, REPOST_PERIOD_MS)
    }

    private fun stopOne(id: String) {
        firing.remove(id)
        // Cancel this id's notification specifically.
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.cancel(clockNotifications.alarmFiringNotificationId(id))
        if (firing.isEmpty()) {
            stopAll()
            return
        }
        // Promote another id if the primary just stopped.
        if (primaryId == id) primaryId = firing.keys.firstOrNull()
    }

    private fun stopAll() {
        firing.clear()
        primaryId = null
        handler.removeCallbacks(repostTick)
        stopSoundAndVibrate()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun snoozeOne(id: String) {
        // Schedule a re-fire of THIS alarm id in SNOOZE_MS via AlarmManager.
        // We deliberately re-use the alarm-fire PendingIntent path so the
        // existing ClockEventReceiver logic handles the re-ring identically
        // to the original fire.
        val fireAt = System.currentTimeMillis() + SNOOZE_MS
        alarmScheduler.scheduleAlarm(id, fireAt)
        stopOne(id)
    }

    private fun startSoundAndVibrate() {
        if (mediaPlayer == null) {
            try {
                val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(this@AlarmFiringService, uri)
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    isLooping = true
                    prepare()
                    start()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "media player failed; channel-sound will still ring", t)
            }
        }
        if (vibrator == null) {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            try {
                val pattern = longArrayOf(0, 600, 400)
                val effect = VibrationEffect.createWaveform(pattern, 0)
                vibrator?.vibrate(effect)
            } catch (t: Throwable) {
                Log.w(TAG, "vibrator failed", t)
            }
        }
    }

    private fun stopSoundAndVibrate() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Throwable) {
        }
        mediaPlayer = null
        try {
            vibrator?.cancel()
        } catch (_: Throwable) {
        }
        vibrator = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(repostTick)
        stopSoundAndVibrate()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AlarmFiringService"
        const val ACTION_START = "com.contextsolutions.localagent.firing.START"
        const val ACTION_STOP = "com.contextsolutions.localagent.firing.STOP"
        const val ACTION_SNOOZE = "com.contextsolutions.localagent.firing.SNOOZE"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_ALARM_LABEL = "alarm_label"
        const val REPOST_PERIOD_MS = 10_000L
        const val SNOOZE_MS = 10 * 60 * 1000L
    }
}
