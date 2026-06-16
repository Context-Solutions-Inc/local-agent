package com.contextsolutions.localagent.job

import com.contextsolutions.localagent.platform.AgentClock
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Desktop coordinator for jobs (PR #70), mirroring
 * [com.contextsolutions.localagent.clock.ClockService]: it keeps the repository,
 * the [DesktopJobScheduler], the cron evaluator, and the [JobExecutor] in sync so
 * callers (UI ViewModel, sync apply-from-peer, startup re-armer) don't have to.
 *
 *  - **Create / edit / delete / run-now** are desktop-only (the §2 trust boundary).
 *  - **Pause/resume** ([setPaused]) cancels or re-arms the job's coroutine.
 *  - **A mobile-originated pause** lands via the sync service's
 *    `onJobPausedFromPeer` seam → [reactToPausedChange] (the DB row was already
 *    written by the raw `updatePausedFromPeer` query; this only drives the
 *    scheduler).
 *  - **Startup**: [rearmAll] walks the repo and arms every non-paused, non-deleted
 *    job at its next occurrence. CRON catch-up = skip-to-next (no backfill).
 */
class JobService(
    private val repository: JobRepository,
    private val scheduler: DesktopJobScheduler,
    private val executor: JobExecutor,
    private val clock: AgentClock = AgentClock(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val logger: (String) -> Unit = {},
) : JobAdmin {

    // ---- Create / edit / delete (desktop-only) -----------------------------

    override suspend fun create(
        name: String,
        command: String,
        prompt: String,
        workingDir: String?,
        scheduleType: JobScheduleType,
        cronExpression: String?,
        fireAtEpochMs: Long?,
    ): Job {
        val now = clock.nowEpochMs()
        val job = Job(
            id = "job-${newId()}",
            name = name,
            command = command,
            prompt = prompt,
            workingDir = workingDir?.takeIf { it.isNotBlank() },
            scheduleType = scheduleType,
            cronExpression = cronExpression?.takeIf { it.isNotBlank() },
            fireAtEpochMs = fireAtEpochMs,
            paused = false,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            deletedAtEpochMs = null,
            lastRunStatus = null,
            lastRunAtEpochMs = null,
            lastRunSummary = null,
            lastRunConversationId = null,
        )
        logger("create id=${job.id} name='${job.name}' schedule=${job.scheduleType} cron=${job.cronExpression} fireAt=${job.fireAtEpochMs}")
        repository.create(job)
        arm(job)
        return job
    }

    override suspend fun update(
        id: String,
        name: String,
        command: String,
        prompt: String,
        workingDir: String?,
        scheduleType: JobScheduleType,
        cronExpression: String?,
        fireAtEpochMs: Long?,
    ) {
        repository.updateDefinition(
            id = id,
            name = name,
            command = command,
            prompt = prompt,
            workingDir = workingDir?.takeIf { it.isNotBlank() },
            scheduleType = scheduleType,
            cronExpression = cronExpression?.takeIf { it.isNotBlank() },
            fireAtEpochMs = fireAtEpochMs,
            nowEpochMs = clock.nowEpochMs(),
        )
        repository.get(id)?.let { arm(it) }
    }

    override suspend fun delete(id: String) {
        scheduler.cancel(id)
        repository.deleteRunsForJob(id)
        repository.softDelete(id, clock.nowEpochMs())
    }

    // ---- Pause / resume ----------------------------------------------------

    override suspend fun setPaused(id: String, paused: Boolean) {
        repository.setPaused(id, paused, clock.nowEpochMs())
        applyPausedToScheduler(id, paused)
    }

    /** Drive the scheduler after a peer (mobile) pause toggle already wrote the row. */
    fun reactToPausedChange(id: String, paused: Boolean) {
        scope.launch { applyPausedToScheduler(id, paused) }
    }

    private suspend fun applyPausedToScheduler(id: String, paused: Boolean) {
        if (paused) {
            scheduler.cancel(id)
        } else {
            repository.get(id)?.let { arm(it) }
        }
    }

    // ---- Run now (desktop-only) --------------------------------------------

    override fun runNow(id: String) {
        scope.launch {
            val job = repository.get(id) ?: return@launch
            if (job.deletedAtEpochMs != null) return@launch
            executor.execute(job)
        }
    }

    // ---- Scheduler fire path -----------------------------------------------

    /** Called by [DesktopJobScheduler] when a job's instant arrives. */
    suspend fun onJobFired(id: String) {
        val job = repository.get(id) ?: return
        if (job.deletedAtEpochMs != null || job.paused) return
        executor.execute(job)
        // Re-arm recurring jobs for the next occurrence. One-shots simply don't
        // re-arm (their fireAt is now in the past), so they never fire again.
        if (job.scheduleType == JobScheduleType.CRON) {
            repository.get(id)?.let { arm(it) }
        }
    }

    // ---- Startup -----------------------------------------------------------

    suspend fun rearmAll() {
        scheduler.cancelAll()
        for (job in repository.snapshot()) {
            arm(job)
        }
    }

    private fun arm(job: Job) {
        if (job.deletedAtEpochMs != null || job.paused) {
            logger("arm skipped id=${job.id} (deleted=${job.deletedAtEpochMs != null} paused=${job.paused})")
            scheduler.cancel(job.id)
            return
        }
        val next = nextFire(job) ?: run {
            logger("arm skipped id=${job.id} — no future fire (schedule=${job.scheduleType} cron='${job.cronExpression}' fireAt=${job.fireAtEpochMs})")
            scheduler.cancel(job.id)
            return
        }
        logger("arm id=${job.id} nextFire=$next")
        scheduler.schedule(job.id, next)
    }

    /** Next fire instant strictly in the future, or null (past one-shot / bad cron). */
    private fun nextFire(job: Job): Long? {
        val now = clock.nowEpochMs()
        return when (job.scheduleType) {
            JobScheduleType.ONE_SHOT -> job.fireAtEpochMs?.takeIf { it > now }
            JobScheduleType.CRON -> job.cronExpression?.let { CronNextFire.next(it, now) }
        }
    }
}
