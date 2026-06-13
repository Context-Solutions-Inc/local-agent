package com.contextsolutions.mobileagent.job

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.contextsolutions.mobileagent.agent.ChatMessage
import com.contextsolutions.mobileagent.conversation.SqlDelightConversationRepository
import com.contextsolutions.mobileagent.db.MobileAgentDatabase
import com.contextsolutions.mobileagent.sync.LocalChangeBus
import com.contextsolutions.mobileagent.telemetry.NoOpTelemetryCounters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Verifies the core PR #70 data flow on a POSIX host: a job runs its command with
 * the prompt passed as an argument, the response is captured, and a run
 * conversation (user = prompt, assistant = response) is created and linked to the
 * job's last run. Uses `echo`, so it's POSIX-only (skips on Windows).
 */
class JobExecutorTest {

    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    @Test
    fun runsCommandWithPromptArgAndRecordsConversation() = runBlocking {
        if (isWindows) return@runBlocking // `echo`/`sh` argv form is POSIX-only.

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MobileAgentDatabase.Schema.create(driver)
        val db = MobileAgentDatabase(driver)
        val jobs = SqlDelightJobRepository(db.jobsQueries, LocalChangeBus())
        val conversations = SqlDelightConversationRepository(db.conversationsQueries, NoOpTelemetryCounters)

        val job = Job(
            id = "job-echo",
            name = "echo job",
            command = "echo",          // → `sh -c 'echo "$1"' sh "<prompt>"`
            prompt = "hello from job",
            workingDir = null,
            scheduleType = JobScheduleType.ONE_SHOT,
            cronExpression = null,
            fireAtEpochMs = 1,
            paused = false,
            createdAtEpochMs = 1,
            updatedAtEpochMs = 1,
            deletedAtEpochMs = null,
            lastRunStatus = null,
            lastRunAtEpochMs = null,
            lastRunSummary = null,
            lastRunConversationId = null,
        )
        jobs.create(job)

        JobExecutor(jobs = jobs, conversations = conversations).execute(job)

        // The job's last-run is recorded + linked to a conversation.
        val updated = jobs.get("job-echo")!!
        assertEquals(JobRunStatus.SUCCEEDED, updated.lastRunStatus)
        val convId = updated.lastRunConversationId
        assertTrue(convId != null, "last run conversation id is set")

        // The conversation holds user = prompt, assistant = response (echoed prompt).
        val messages = conversations.loadMessages(convId!!)
        assertEquals(2, messages.size)
        val user = messages[0]
        val assistant = messages[1]
        assertTrue(user is ChatMessage.User)
        assertEquals("hello from job", (user as ChatMessage.User).text)
        assertTrue(assistant is ChatMessage.Assistant)
        assertEquals("hello from job", (assistant as ChatMessage.Assistant).text)
        assertEquals(true, assistant.renderMarkdown)

        // A finished run row links the same conversation.
        val run = jobs.runsForJob("job-echo").single()
        assertEquals(JobRunStatus.SUCCEEDED, run.status)
        assertEquals(0, run.exitCode)
        assertEquals(convId, run.conversationId)
    }

    @Test
    fun successiveRunsContinueTheSameConversationThread() = runBlocking {
        if (isWindows) return@runBlocking

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MobileAgentDatabase.Schema.create(driver)
        val db = MobileAgentDatabase(driver)
        val jobs = SqlDelightJobRepository(db.jobsQueries, LocalChangeBus())
        val conversations = SqlDelightConversationRepository(db.conversationsQueries, NoOpTelemetryCounters)
        val executor = JobExecutor(jobs = jobs, conversations = conversations)

        val job = Job(
            id = "job-twice", name = "echo twice", command = "echo", prompt = "ping",
            workingDir = null, scheduleType = JobScheduleType.ONE_SHOT, cronExpression = null,
            fireAtEpochMs = 1, paused = false, createdAtEpochMs = 1, updatedAtEpochMs = 1,
            deletedAtEpochMs = null, lastRunStatus = null, lastRunAtEpochMs = null,
            lastRunSummary = null, lastRunConversationId = null,
        )
        jobs.create(job)

        executor.execute(job)
        val afterFirst = jobs.get("job-twice")!!
        // Second run uses the job as the scheduler would re-read it (with the
        // thread now linked).
        executor.execute(afterFirst)
        val afterSecond = jobs.get("job-twice")!!

        // Same conversation across both runs, now holding 2 user + 2 assistant turns.
        assertEquals(afterFirst.lastRunConversationId, afterSecond.lastRunConversationId)
        val messages = conversations.loadMessages(afterSecond.lastRunConversationId!!)
        assertEquals(4, messages.size)
        assertEquals(2, jobs.runsForJob("job-twice").size)
    }
}
