package com.contextsolutions.localagent.job

/**
 * A **job** (PR #70) is time-triggered work that runs **only on the desktop
 * agent**. It invokes [command] as a subprocess, passing [prompt] as a command
 * argument, and records the response. Each run also creates a real conversation
 * (user = prompt, assistant = response) that syncs via the existing conversation
 * path; the latest run is denormalized onto the job row ([lastRunStatus] /
 * [lastRunAtEpochMs] / [lastRunSummary] / [lastRunConversationId]) so the mobile
 * remote-view can show "last result" and open the run conversation with only the
 * synced job record.
 *
 * Trust boundary (docs/JOBS_PLAN.md §2): create / edit / delete / run-now happen
 * ONLY on the desktop. A mobile peer may toggle [paused] on an existing job and
 * nothing else.
 *
 * Stable [id] + [updatedAtEpochMs] + nullable [deletedAtEpochMs] tombstone mirror
 * the other synced records (conversations/memories) for last-write-wins sync.
 */
data class Job(
    val id: String,
    val name: String,
    val command: String,
    val prompt: String,
    val workingDir: String?,
    val scheduleType: JobScheduleType,
    val cronExpression: String?,
    val fireAtEpochMs: Long?,
    val paused: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val deletedAtEpochMs: Long?,
    val lastRunStatus: JobRunStatus?,
    val lastRunAtEpochMs: Long?,
    val lastRunSummary: String?,
    val lastRunConversationId: String?,
)

enum class JobScheduleType {
    /** Recurring runs from a 5-field cron expression ([Job.cronExpression]). */
    CRON,

    /** A single run at an absolute instant ([Job.fireAtEpochMs]). */
    ONE_SHOT,
}

/**
 * One execution of a job. Desktop-local history (NOT synced) — the run
 * conversation it references syncs through the normal conversation path, and the
 * latest run is denormalized onto the [Job] row for the mobile view.
 */
data class JobRun(
    val id: String,
    val jobId: String,
    val conversationId: String?,
    val status: JobRunStatus,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long?,
    val exitCode: Int?,
    val response: String?,
    val error: String?,
)

enum class JobRunStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    ;

    val isTerminal: Boolean get() = this != RUNNING
}
