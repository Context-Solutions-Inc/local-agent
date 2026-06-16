package com.contextsolutions.localagent.job

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android [JobNotificationPrefs] backed by a tiny SharedPreferences file (PR #85),
 * mirroring [com.contextsolutions.localagent.sync.SharedPreferencesSyncWatermarkStore].
 * The "seen" watermark is mirrored into a [MutableStateFlow] seeded from disk so the
 * header badge reacts to [setSeenWatermark] without a re-read.
 */
class SharedPreferencesJobNotificationPrefs(context: Context) : JobNotificationPrefs {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _seen = MutableStateFlow(prefs.getLong(KEY_SEEN, 0L))
    override val seenWatermark: StateFlow<Long> = _seen.asStateFlow()

    override fun setSeenWatermark(value: Long) {
        prefs.edit().putLong(KEY_SEEN, value).apply()
        _seen.value = value
    }

    override fun notifiedWatermark(): Long = prefs.getLong(KEY_NOTIFIED, 0L)

    override fun setNotifiedWatermark(value: Long) {
        prefs.edit().putLong(KEY_NOTIFIED, value).apply()
    }

    private companion object {
        const val PREFS_NAME = "job_notify_prefs"
        const val KEY_SEEN = "seen_watermark_ms"
        const val KEY_NOTIFIED = "notified_watermark_ms"
    }
}
