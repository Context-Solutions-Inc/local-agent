package com.contextsolutions.mobileagent.job

import com.contextsolutions.mobileagent.platform.DesktopJsonStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop [JobNotificationPrefs] backed by a [DesktopJsonStore] file (PR #93),
 * mirroring [SharedPreferencesJobNotificationPrefs] (Android) and
 * [com.contextsolutions.mobileagent.sync.DesktopSyncWatermarkStore].
 *
 * The desktop has no Jobs header badge, so [seenWatermark] / [setSeenWatermark]
 * exist only to satisfy the interface (inert). The real one is
 * [notifiedWatermark]: it dedupes the desktop job-completion notification across
 * reconciles + restarts. PR #93 wires [JobCompletionNotifier] on the desktop too
 * (it was Android-only) so a finished job surfaces a notify-send notification on
 * the machine that actually ran it.
 */
class DesktopJobNotificationPrefs(private val store: DesktopJsonStore) : JobNotificationPrefs {
    private val _seen = MutableStateFlow(store.getString(KEY_SEEN)?.toLongOrNull() ?: 0L)
    override val seenWatermark: StateFlow<Long> = _seen.asStateFlow()

    override fun setSeenWatermark(value: Long) {
        store.putString(KEY_SEEN, value.toString())
        _seen.value = value
    }

    override fun notifiedWatermark(): Long = store.getString(KEY_NOTIFIED)?.toLongOrNull() ?: 0L

    override fun setNotifiedWatermark(value: Long) {
        store.putString(KEY_NOTIFIED, value.toString())
    }

    private companion object {
        const val KEY_SEEN = "seen_watermark_ms"
        const val KEY_NOTIFIED = "notified_watermark_ms"
    }
}
