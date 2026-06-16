package com.contextsolutions.localagent.job

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * A run worth telling the user about (PR #85): a finished run that SUCCEEDED or
 * FAILED. RUNNING (mid-run) and CANCELLED (user aborted) are deliberately not
 * noteworthy — neither badge nor notification fires for them. Returns the run's
 * `lastRunAtEpochMs` (the watermark dimension), or null when not noteworthy.
 */
internal fun Job.noteworthyRunAtEpochMs(): Long? {
    val at = lastRunAtEpochMs ?: return null
    return when (lastRunStatus) {
        JobRunStatus.SUCCEEDED, JobRunStatus.FAILED -> at
        else -> null
    }
}

/**
 * Drives the chat-header Jobs-icon count bubble (PR #85, mobile-only). [unseenCount]
 * is the number of jobs whose latest run is noteworthy ([noteworthyRunAtEpochMs])
 * and newer than the persisted "seen" watermark — rendered as a numbered bubble on
 * the Jobs icon (the same style the alarm/timer/todo icons used pre-PR #26).
 * Opening the Jobs screen calls [markSeen], which advances the watermark to the
 * newest noteworthy run → the count drops to 0 and the bubble disappears.
 *
 * App-lifetime singleton with its own scope (the badge must stay live regardless of
 * which screen is mounted). Reads the same reactive [JobRepository.flow] the Jobs
 * screen does, so it reflects synced desktop completions immediately.
 */
class JobBadge(
    private val repository: JobRepository,
    private val prefs: JobNotificationPrefs,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    val unseenCount: StateFlow<Int> =
        combine(repository.flow(), prefs.seenWatermark) { jobs, seen ->
            jobs.count { (it.noteworthyRunAtEpochMs() ?: 0L) > seen }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), 0)

    /** The user has looked at the Jobs screen — mark every current run as seen. */
    suspend fun markSeen() {
        val max = repository.snapshot().mapNotNull { it.noteworthyRunAtEpochMs() }.maxOrNull() ?: return
        if (max > prefs.seenWatermark.value) prefs.setSeenWatermark(max)
    }
}
