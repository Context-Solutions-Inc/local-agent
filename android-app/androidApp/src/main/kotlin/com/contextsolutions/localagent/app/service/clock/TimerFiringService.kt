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
import com.contextsolutions.localagent.clock.ClockService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Foreground service that drives a ringing **timer** (PR #22). Mirrors
 * [AlarmFiringService]: timers used to fire a single silent-on-DND chime; now
 * they ring continuously like an alarm until the user acts.
 *
 *  - Plays the default alarm sound on loop (USAGE_ALARM stream — bypasses
 *    silent mode like an alarm clock).
 *  - Vibrates on a repeating pattern.
 *  - Re-posts the firing notification every 10 s so the channel sound
 *    re-asserts on OEM skins that suppress repeat sounds within one id.
 *
 * Stops on user action:
 *  - **Stop** ([ACTION_STOP]): silence + clear the notification for that timer.
 *  - **+1 minute** ([ACTION_ADD_MINUTE]): silence this ring and arm a fresh
 *    60 s timer (via [ClockService.createTimer], preserving the label) — the
 *    timer analogue of snooze, but additive rather than a fixed snooze window.
 *
 * Multiple timers firing at once are tracked by `timerId` (id → label), each
 * with its own notification; the looping sound/vibrator are shared (one ring at
 * a time is what users want). Foreground type `mediaPlayback` matches the
 * looping ringtone playback (same as [AlarmFiringService]).
 */
class TimerFiringService : Service(), KoinComponent {

    private val clockNotifications: ClockNotifications by inject()
    private val clockService: ClockService by inject()

    private val handler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    /** Active timers currently ringing: id → label. */
    private val firing = mutableMapOf<String, String?>()

    /** The id currently driving the foreground notification (the "primary" ring). */
    private var primaryId: String? = null

    private val repostTick = object : Runnable {
        override fun run() {
            val pid = primaryId ?: return
            val label = firing[pid]
            val notification = clockNotifications.buildTimerFiringNotification(pid, label)
            try {
                val mgr = getSystemService(NotificationManager::class.java)
                mgr.notify(clockNotifications.timerNotificationId(pid), notification)
            } catch (t: Throwable) {
                Log.w(TAG, "notification re-post failed", t)
            }
            handler.postDelayed(this, REPOST_PERIOD_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val id = intent?.getStringExtra(EXTRA_TIMER_ID)
        when (action) {
            ACTION_START -> {
                if (id == null) return START_NOT_STICKY
                startFiring(id, intent.getStringExtra(EXTRA_TIMER_LABEL))
            }
            ACTION_STOP -> if (id != null) stopOne(id) else stopAll()
            ACTION_ADD_MINUTE -> if (id != null) addMinute(id) else stopAll()
            else -> Log.w(TAG, "unexpected action $action")
        }
        return START_NOT_STICKY
    }

    private fun startFiring(id: String, label: String?) {
        firing[id] = label
        if (primaryId == null) primaryId = id

        val pid = primaryId ?: id
        val notification = clockNotifications.buildTimerFiringNotification(pid, firing[pid])
        val notificationId = clockNotifications.timerNotificationId(pid)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(notificationId, notification)
        }
        startSoundAndVibrate()
        handler.removeCallbacks(repostTick)
        handler.postDelayed(repostTick, REPOST_PERIOD_MS)
    }

    private fun stopOne(id: String) {
        firing.remove(id)
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.cancel(clockNotifications.timerNotificationId(id))
        if (firing.isEmpty()) {
            stopAll()
            return
        }
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

    /** Silence this ring and arm a fresh 60 s timer with the same label. */
    private fun addMinute(id: String) {
        val label = firing[id]
        try {
            clockService.createTimer(ADD_MINUTE_MS, label)
        } catch (t: Throwable) {
            Log.w(TAG, "failed to arm +1 minute timer", t)
        }
        stopOne(id)
    }

    private fun startSoundAndVibrate() {
        if (mediaPlayer == null) {
            try {
                val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(this@TimerFiringService, uri)
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
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
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
        private const val TAG = "TimerFiringService"
        const val ACTION_START = "com.contextsolutions.localagent.firing.TIMER_START"
        const val ACTION_STOP = "com.contextsolutions.localagent.firing.TIMER_STOP"
        const val ACTION_ADD_MINUTE = "com.contextsolutions.localagent.firing.TIMER_ADD_MINUTE"
        const val EXTRA_TIMER_ID = "timer_id"
        const val EXTRA_TIMER_LABEL = "timer_label"
        const val REPOST_PERIOD_MS = 10_000L
        const val ADD_MINUTE_MS = 60_000L
    }
}
