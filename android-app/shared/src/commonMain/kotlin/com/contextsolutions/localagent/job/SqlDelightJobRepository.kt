package com.contextsolutions.localagent.job

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.contextsolutions.localagent.db.JobsQueries
import com.contextsolutions.localagent.sync.LocalChangeBus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * SQLDelight-backed [JobRepository] (PR #70). Used identically on both platforms
 * (it talks to the shared `:shared` stores); the desktop additionally drives the
 * scheduler/executor on top of it.
 *
 * [flow] is a **SQLDelight reactive query** (`asFlow().mapToList`), so it re-emits
 * on every write to the `jobs` table on this driver. Critically that includes the
 * sync apply-from-peer path, which writes the `*FromPeer` queries on [JobsQueries]
 * directly (NOT through this repo) — a synced desktop job must light up the phone's
 * list live, and a manually-seeded StateFlow would stay stale until restart.
 *
 * Genuine-local writes also notify [bus] so [com.contextsolutions.localagent.sync.SyncController]
 * pushes them; the raw `*FromPeer` path deliberately does NOT fire the bus (no echo).
 */
class SqlDelightJobRepository(
    private val queries: JobsQueries,
    private val bus: LocalChangeBus,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : JobRepository {

    override fun flow(): Flow<List<Job>> =
        queries.selectAllJobs().asFlow().mapToList(ioDispatcher).map { rows -> rows.map(::rowToJob) }

    override suspend fun snapshot(): List<Job> = withContext(ioDispatcher) { readAll() }

    override suspend fun get(id: String): Job? = withContext(ioDispatcher) {
        queries.selectJobById(id).executeAsOneOrNull()?.let(::rowToJob)
    }

    override suspend fun create(job: Job) {
        withContext(ioDispatcher) {
            queries.insertJob(
                id = job.id,
                name = job.name,
                command = job.command,
                prompt = job.prompt,
                working_dir = job.workingDir,
                schedule_type = job.scheduleType.name,
                cron_expression = job.cronExpression,
                fire_at_epoch_ms = job.fireAtEpochMs,
                paused = if (job.paused) 1L else 0L,
                created_at_epoch_ms = job.createdAtEpochMs,
                updated_at_epoch_ms = job.updatedAtEpochMs,
            )
            republish()
        }
    }

    override suspend fun updateDefinition(
        id: String,
        name: String,
        command: String,
        prompt: String,
        workingDir: String?,
        scheduleType: JobScheduleType,
        cronExpression: String?,
        fireAtEpochMs: Long?,
        nowEpochMs: Long,
    ) {
        withContext(ioDispatcher) {
            queries.updateJob(
                name = name,
                command = command,
                prompt = prompt,
                working_dir = workingDir,
                schedule_type = scheduleType.name,
                cron_expression = cronExpression,
                fire_at_epoch_ms = fireAtEpochMs,
                updated_at_epoch_ms = nowEpochMs,
                id = id,
            )
            republish()
        }
    }

    override suspend fun setPaused(id: String, paused: Boolean, nowEpochMs: Long) {
        withContext(ioDispatcher) {
            queries.updatePaused(paused = if (paused) 1L else 0L, updated_at_epoch_ms = nowEpochMs, id = id)
            republish()
        }
    }

    override suspend fun softDelete(id: String, nowEpochMs: Long) {
        withContext(ioDispatcher) {
            queries.softDeleteJob(deleted_at_epoch_ms = nowEpochMs, updated_at_epoch_ms = nowEpochMs, id = id)
            republish()
        }
    }

    override suspend fun recordLastRun(
        id: String,
        status: JobRunStatus,
        atEpochMs: Long,
        summary: String?,
        conversationId: String?,
        nowEpochMs: Long,
    ) {
        withContext(ioDispatcher) {
            queries.updateLastRun(
                last_run_status = status.name,
                last_run_at_epoch_ms = atEpochMs,
                last_run_summary = summary,
                last_run_conversation_id = conversationId,
                updated_at_epoch_ms = nowEpochMs,
                id = id,
            )
            republish()
        }
    }

    override suspend fun insertRun(run: JobRun) {
        withContext(ioDispatcher) {
            queries.insertRun(
                id = run.id,
                job_id = run.jobId,
                conversation_id = run.conversationId,
                status = run.status.name,
                started_at_epoch_ms = run.startedAtEpochMs,
            )
        }
    }

    override suspend fun finishRun(
        id: String,
        status: JobRunStatus,
        finishedAtEpochMs: Long,
        exitCode: Int?,
        response: String?,
        error: String?,
    ) {
        withContext(ioDispatcher) {
            queries.finishRun(
                status = status.name,
                finished_at_epoch_ms = finishedAtEpochMs,
                exit_code = exitCode?.toLong(),
                response = response,
                error = error,
                id = id,
            )
        }
    }

    override suspend fun runsForJob(jobId: String): List<JobRun> = withContext(ioDispatcher) {
        queries.selectRunsForJob(jobId).executeAsList().map(::rowToRun)
    }

    override suspend fun deleteRunsForJob(jobId: String) {
        withContext(ioDispatcher) { queries.deleteRunsForJob(jobId) }
    }

    // [flow] is reactive, so a genuine-local write needs only to notify the sync
    // bus (the reactive query repaints the UI on its own).
    private fun republish() {
        bus.notifyChanged()
    }

    private fun readAll(): List<Job> = queries.selectAllJobs().executeAsList().map(::rowToJob)

    private fun rowToJob(row: com.contextsolutions.localagent.db.Jobs): Job = Job(
        id = row.id,
        name = row.name,
        command = row.command,
        prompt = row.prompt,
        workingDir = row.working_dir,
        scheduleType = parseSchedule(row.schedule_type),
        cronExpression = row.cron_expression,
        fireAtEpochMs = row.fire_at_epoch_ms,
        paused = row.paused != 0L,
        createdAtEpochMs = row.created_at_epoch_ms,
        updatedAtEpochMs = row.updated_at_epoch_ms,
        deletedAtEpochMs = row.deleted_at_epoch_ms,
        lastRunStatus = row.last_run_status?.let(::parseRunStatus),
        lastRunAtEpochMs = row.last_run_at_epoch_ms,
        lastRunSummary = row.last_run_summary,
        lastRunConversationId = row.last_run_conversation_id,
    )

    private fun rowToRun(row: com.contextsolutions.localagent.db.Job_runs): JobRun = JobRun(
        id = row.id,
        jobId = row.job_id,
        conversationId = row.conversation_id,
        status = parseRunStatus(row.status),
        startedAtEpochMs = row.started_at_epoch_ms,
        finishedAtEpochMs = row.finished_at_epoch_ms,
        exitCode = row.exit_code?.toInt(),
        response = row.response,
        error = row.error,
    )

    private fun parseSchedule(raw: String): JobScheduleType =
        runCatching { JobScheduleType.valueOf(raw.uppercase()) }.getOrDefault(JobScheduleType.ONE_SHOT)

    private fun parseRunStatus(raw: String): JobRunStatus =
        runCatching { JobRunStatus.valueOf(raw.uppercase()) }.getOrDefault(JobRunStatus.FAILED)
}
