package com.contextsolutions.mobileagent.job

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Minimal in-memory [JobRepository] for the PR #85 badge/notifier tests. Only the
 * read path ([flow]/[snapshot]) is exercised; mutations are no-ops. [emit] simulates
 * a sync apply-from-peer landing new rows (the reactive query re-emitting).
 */
class FakeJobRepository(initial: List<Job> = emptyList()) : JobRepository {
    private val state = MutableStateFlow(initial)

    fun emit(jobs: List<Job>) { state.value = jobs }

    override fun flow(): Flow<List<Job>> = state
    override suspend fun snapshot(): List<Job> = state.value
    override suspend fun get(id: String): Job? = state.value.firstOrNull { it.id == id }

    override suspend fun create(job: Job) = Unit
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
    ) = Unit
    override suspend fun setPaused(id: String, paused: Boolean, nowEpochMs: Long) = Unit
    override suspend fun softDelete(id: String, nowEpochMs: Long) = Unit
    override suspend fun recordLastRun(
        id: String,
        status: JobRunStatus,
        atEpochMs: Long,
        summary: String?,
        conversationId: String?,
        nowEpochMs: Long,
    ) = Unit
    override suspend fun insertRun(run: JobRun) = Unit
    override suspend fun finishRun(
        id: String,
        status: JobRunStatus,
        finishedAtEpochMs: Long,
        exitCode: Int?,
        response: String?,
        error: String?,
    ) = Unit
    override suspend fun runsForJob(jobId: String): List<JobRun> = emptyList()
    override suspend fun deleteRunsForJob(jobId: String) = Unit
}

/** In-memory [JobNotificationPrefs] for tests; [seedNotified] simulates a prior process run. */
class FakeJobNotificationPrefs(seenInit: Long = 0L, seedNotified: Long = 0L) : JobNotificationPrefs {
    private val seen = MutableStateFlow(seenInit)
    private var notified = seedNotified
    override val seenWatermark = seen
    override fun setSeenWatermark(value: Long) { seen.value = value }
    override fun notifiedWatermark(): Long = notified
    override fun setNotifiedWatermark(value: Long) { notified = value }
}

/** Build a [Job] with only the run fields that the badge/notifier care about. */
fun jobWithRun(
    id: String,
    status: JobRunStatus?,
    atEpochMs: Long?,
    name: String = "Job $id",
    summary: String? = null,
): Job = Job(
    id = id,
    name = name,
    command = "echo",
    prompt = "",
    workingDir = null,
    scheduleType = JobScheduleType.ONE_SHOT,
    cronExpression = null,
    fireAtEpochMs = null,
    paused = false,
    createdAtEpochMs = 0L,
    updatedAtEpochMs = atEpochMs ?: 0L,
    deletedAtEpochMs = null,
    lastRunStatus = status,
    lastRunAtEpochMs = atEpochMs,
    lastRunSummary = summary,
    lastRunConversationId = null,
)
