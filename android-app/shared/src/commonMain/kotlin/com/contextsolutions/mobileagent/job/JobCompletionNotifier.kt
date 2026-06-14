package com.contextsolutions.mobileagent.job

import com.contextsolutions.mobileagent.notification.AppNotification
import com.contextsolutions.mobileagent.notification.NotificationKind
import com.contextsolutions.mobileagent.notification.NotificationPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.launch

/**
 * Raises an Android OS notification when a desktop job run completes (PR #85,
 * mobile-only, foreground-only). Jobs finish on the desktop and their terminal
 * status lands on the phone via sync, which re-emits [JobRepository.flow]; this
 * observes that flow and turns each *newly* noteworthy run (SUCCEEDED/FAILED — see
 * [noteworthyRunAtEpochMs]) into one [NotificationPresenter.present] call.
 *
 * Dedup is by the [JobNotificationPrefs.notifiedWatermark] (highest noteworthy
 * `lastRunAtEpochMs` already alerted), so a reconcile that re-emits the same rows,
 * or a process restart, never re-notifies. The very first emission of a process run
 * is a **baseline**: the watermark jumps to the current max without notifying, so
 * the initial sync backfill of already-finished jobs doesn't produce a storm.
 *
 * [start] is driven by the foreground gate (start on first activity, cancel on
 * last) so notifications only fire while the app is foregrounded and syncing.
 */
class JobCompletionNotifier(
    private val repository: JobRepository,
    private val presenter: NotificationPresenter,
    private val prefs: JobNotificationPrefs,
    private val logger: (String) -> Unit = {},
) {
    /** Launch the observer on [scope]; returns its [CoroutineJob] so the caller can cancel it. */
    fun start(scope: CoroutineScope): CoroutineJob = scope.launch {
        var baselineDone = false
        repository.flow().collect { jobs ->
            val threshold = prefs.notifiedWatermark()
            val fresh = jobs
                .mapNotNull { job -> job.noteworthyRunAtEpochMs()?.let { at -> job to at } }
                .filter { (_, at) -> at > threshold }

            if (!baselineDone) {
                baselineDone = true
                val max = fresh.maxOfOrNull { it.second }
                if (max != null && max > threshold) prefs.setNotifiedWatermark(max)
                logger("baseline: suppressed ${fresh.size} already-finished run(s), watermark=${prefs.notifiedWatermark()}")
                return@collect
            }

            if (fresh.isEmpty()) return@collect

            for ((job, _) in fresh.sortedBy { it.second }) notify(job)
            val max = fresh.maxOf { it.second }
            if (max > threshold) prefs.setNotifiedWatermark(max)
            logger("notified ${fresh.size} completed run(s), watermark=${prefs.notifiedWatermark()}")
        }
    }

    private fun notify(job: Job) {
        val succeeded = job.lastRunStatus == JobRunStatus.SUCCEEDED
        val title = if (succeeded) "Job finished" else "Job failed"
        val body = buildString {
            append(job.name)
            val summary = job.lastRunSummary?.trim()?.takeIf { it.isNotEmpty() }
            if (summary != null) {
                append(" — ")
                append(if (summary.length > 140) summary.take(139) + "…" else summary)
            }
        }
        presenter.present(
            AppNotification(
                id = "job_run:${job.id}",
                title = title,
                body = body,
                kind = NotificationKind.JOB,
            ),
        )
    }
}
