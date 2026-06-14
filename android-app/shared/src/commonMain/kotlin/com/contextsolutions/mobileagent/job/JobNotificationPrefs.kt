package com.contextsolutions.mobileagent.job

import kotlinx.coroutines.flow.StateFlow

/**
 * Mobile-only persistence for the two "job run completed" watermarks (PR #85).
 * Jobs run on the desktop and their terminal status reaches the phone via sync;
 * these epoch-ms watermarks let the phone decide what's *unseen* / *un-notified*
 * without re-alerting on every reconcile or initial backfill.
 *
 * Two independent watermarks, both = the highest noteworthy (SUCCEEDED/FAILED)
 * `lastRunAtEpochMs` already accounted for:
 *  - [seenWatermark] (reactive) drives the chat-header badge dot; advanced when the
 *    user opens the Jobs screen ([setSeenWatermark]).
 *  - [notifiedWatermark] (plain read/write, touched only by [JobCompletionNotifier])
 *    dedupes the Android OS notification across reconciles + process restarts.
 *
 * Mirrors [com.contextsolutions.mobileagent.sync.SyncWatermarkStore]; the Android
 * impl is a tiny SharedPreferences file. Bound only on mobile (the desktop neither
 * badges nor notifies for jobs).
 */
interface JobNotificationPrefs {
    /** Reactive "seen" watermark for the header badge. */
    val seenWatermark: StateFlow<Long>

    /** Advance the "seen" watermark (the user has looked at the Jobs screen). */
    fun setSeenWatermark(value: Long)

    /** Highest noteworthy run already turned into an OS notification. */
    fun notifiedWatermark(): Long

    /** Persist the "notified" watermark. */
    fun setNotifiedWatermark(value: Long)
}
