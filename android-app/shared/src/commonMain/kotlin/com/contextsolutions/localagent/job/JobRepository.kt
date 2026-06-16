package com.contextsolutions.localagent.job

import kotlinx.coroutines.flow.Flow

/**
 * Persistence for jobs (PR #70), backed by the `jobs` + `job_runs` tables
 * (SQLDelight). Lives in commonMain with the SQLDelight impl: **both platforms**
 * store rows so the mobile app renders synced state offline, while the desktop is
 * authoritative for definitions and owns the scheduler/executor.
 *
 * Mirrors [com.contextsolutions.localagent.task.TaskRepository]: a synchronously
 * seeded [StateFlow] republished on every mutation. Genuine-local writes
 * ([create]/[setPaused]/[updateDefinition]/[softDelete]/[recordLastRun]) fire
 * [com.contextsolutions.localagent.sync.LocalChangeBus] so they push on the next
 * reconcile; the sync apply-from-peer path writes through the raw `*FromPeer`
 * queries directly (no bus → no echo loop).
 */
interface JobRepository {

    /**
     * Live list of non-deleted jobs (creation order). Backed by a SQLDelight
     * reactive query, so it re-emits on ANY write to the `jobs` table on this
     * driver — including the sync apply-from-peer path (`upsertJobFromPeer`), which
     * writes raw queries that bypass this repo. (A plain seeded StateFlow would go
     * stale when a synced desktop job lands on the phone — PR #70 sync display fix.)
     */
    fun flow(): Flow<List<Job>>

    suspend fun snapshot(): List<Job>

    suspend fun get(id: String): Job?

    /** Desktop-only create. */
    suspend fun create(job: Job)

    /** Desktop-only edit of the full definition (bumps updatedAt, fires the bus). */
    suspend fun updateDefinition(
        id: String,
        name: String,
        command: String,
        prompt: String,
        workingDir: String?,
        scheduleType: JobScheduleType,
        cronExpression: String?,
        fireAtEpochMs: Long?,
        nowEpochMs: Long,
    )

    /** Local pause/resume toggle (desktop or mobile). Fires the bus. */
    suspend fun setPaused(id: String, paused: Boolean, nowEpochMs: Long)

    /** Desktop-only soft delete (tombstone) so the delete propagates. Fires the bus. */
    suspend fun softDelete(id: String, nowEpochMs: Long)

    /** Desktop writes the denormalized last-run summary + run conversation id. Fires the bus. */
    suspend fun recordLastRun(
        id: String,
        status: JobRunStatus,
        atEpochMs: Long,
        summary: String?,
        conversationId: String?,
        nowEpochMs: Long,
    )

    // ---- Run history (desktop-local; NOT synced) ---------------------------

    suspend fun insertRun(run: JobRun)

    suspend fun finishRun(
        id: String,
        status: JobRunStatus,
        finishedAtEpochMs: Long,
        exitCode: Int?,
        response: String?,
        error: String?,
    )

    suspend fun runsForJob(jobId: String): List<JobRun>

    /** Prune run history for a tombstoned job (soft delete doesn't fire the FK CASCADE). */
    suspend fun deleteRunsForJob(jobId: String)
}
