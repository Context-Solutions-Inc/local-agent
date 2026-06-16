package com.contextsolutions.localagent.job

/**
 * Desktop-only administration of jobs (PR #70) — create / edit / delete / pause /
 * run-now. The §2 trust boundary: a job is only ever *defined* on the locally
 * trusted desktop, so this seam is bound ONLY in the desktop module (the desktop
 * `JobService` implements it). The shared `:ui` JobsViewModel injects it as
 * nullable (`getOrNull`); a `null` admin = the mobile remote-view, which shows the
 * list read-only and only toggles `paused` through the repository directly.
 *
 * Kept in commonMain (the interface only) so `:ui` can reference it without a
 * dependency on the desktop-only scheduler/executor implementation.
 */
interface JobAdmin {
    suspend fun create(
        name: String,
        command: String,
        prompt: String,
        workingDir: String?,
        scheduleType: JobScheduleType,
        cronExpression: String?,
        fireAtEpochMs: Long?,
    ): Job

    suspend fun update(
        id: String,
        name: String,
        command: String,
        prompt: String,
        workingDir: String?,
        scheduleType: JobScheduleType,
        cronExpression: String?,
        fireAtEpochMs: Long?,
    )

    suspend fun delete(id: String)

    /** Pause/resume on the desktop: writes the row AND drives the scheduler. */
    suspend fun setPaused(id: String, paused: Boolean)

    /** Run the job immediately, off-schedule. */
    fun runNow(id: String)
}
