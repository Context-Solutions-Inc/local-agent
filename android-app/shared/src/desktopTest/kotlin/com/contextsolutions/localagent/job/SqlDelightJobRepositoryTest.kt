package com.contextsolutions.localagent.job

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.contextsolutions.localagent.db.LocalAgentDatabase
import com.contextsolutions.localagent.sync.LocalChangeBus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Exercises [SqlDelightJobRepository] against a real in-memory SQLite DB (PR #70)
 * — verifies the `Jobs.sq` queries + the v10 schema (migration `9.sqm`) end-to-end.
 */
class SqlDelightJobRepositoryTest {

    private fun newRepo(): SqlDelightJobRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalAgentDatabase.Schema.create(driver)
        return SqlDelightJobRepository(LocalAgentDatabase(driver).jobsQueries, LocalChangeBus())
    }

    private fun job(id: String = "job-1") = Job(
        id = id,
        name = "nightly backup",
        command = "echo",
        prompt = "back up now",
        workingDir = "/tmp",
        scheduleType = JobScheduleType.CRON,
        cronExpression = "0 2 * * *",
        fireAtEpochMs = null,
        paused = false,
        createdAtEpochMs = 100,
        updatedAtEpochMs = 100,
        deletedAtEpochMs = null,
        lastRunStatus = null,
        lastRunAtEpochMs = null,
        lastRunSummary = null,
        lastRunConversationId = null,
    )

    @Test
    fun createReadRoundTrip() = runBlocking {
        val repo = newRepo()
        repo.create(job())
        val read = repo.get("job-1")!!
        assertEquals("nightly backup", read.name)
        assertEquals("echo", read.command)
        assertEquals(JobScheduleType.CRON, read.scheduleType)
        assertEquals(listOf("job-1"), repo.snapshot().map { it.id })
    }

    @Test
    fun pauseToggleAndSoftDelete() = runBlocking {
        val repo = newRepo()
        repo.create(job())
        repo.setPaused("job-1", paused = true, nowEpochMs = 200)
        assertTrue(repo.get("job-1")!!.paused)

        repo.softDelete("job-1", nowEpochMs = 300)
        // get() still resolves the tombstoned row (the sync apply path needs to see
        // it for LWW); only the UI-facing snapshot()/flow() filter tombstones out.
        assertEquals(300L, repo.get("job-1")!!.deletedAtEpochMs)
        assertTrue(repo.snapshot().isEmpty())
    }

    @Test
    fun recordLastRunDenormalizesOntoJob() = runBlocking {
        val repo = newRepo()
        repo.create(job())
        repo.recordLastRun(
            id = "job-1",
            status = JobRunStatus.SUCCEEDED,
            atEpochMs = 500,
            summary = "done",
            conversationId = "conv-9",
            nowEpochMs = 500,
        )
        val read = repo.get("job-1")!!
        assertEquals(JobRunStatus.SUCCEEDED, read.lastRunStatus)
        assertEquals("done", read.lastRunSummary)
        assertEquals("conv-9", read.lastRunConversationId)
    }

    @Test
    fun runHistoryInsertFinishAndPrune() = runBlocking {
        val repo = newRepo()
        repo.create(job())
        repo.insertRun(
            JobRun(
                id = "run-1",
                jobId = "job-1",
                conversationId = "conv-1",
                status = JobRunStatus.RUNNING,
                startedAtEpochMs = 10,
                finishedAtEpochMs = null,
                exitCode = null,
                response = null,
                error = null,
            ),
        )
        repo.finishRun("run-1", JobRunStatus.SUCCEEDED, finishedAtEpochMs = 20, exitCode = 0, response = "ok", error = null)
        val runs = repo.runsForJob("job-1")
        assertEquals(1, runs.size)
        assertEquals(JobRunStatus.SUCCEEDED, runs.single().status)
        assertEquals(0, runs.single().exitCode)

        repo.deleteRunsForJob("job-1")
        assertTrue(repo.runsForJob("job-1").isEmpty())
    }
}
