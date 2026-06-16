package com.contextsolutions.localagent.job

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.contextsolutions.localagent.agent.ChatMessage
import com.contextsolutions.localagent.conversation.SqlDelightConversationRepository
import com.contextsolutions.localagent.db.LocalAgentDatabase
import com.contextsolutions.localagent.sync.LocalChangeBus
import com.contextsolutions.localagent.telemetry.NoOpTelemetryCounters
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Verifies the core PR #70 data flow on a POSIX host: a job runs its command with
 * the prompt passed as an argument, the response is captured, and a run
 * conversation (user = job name, assistant = response) is created and linked to the
 * job's last run. Uses `echo`, so it's POSIX-only (skips on Windows).
 */
class JobExecutorTest {

    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    @Test
    fun runsCommandWithPromptArgAndRecordsConversation() = runBlocking {
        if (isWindows) return@runBlocking // `echo`/`sh` argv form is POSIX-only.

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalAgentDatabase.Schema.create(driver)
        val db = LocalAgentDatabase(driver)
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

        // The conversation holds user = job name, assistant = response (echoed prompt).
        val messages = conversations.loadMessages(convId!!)
        assertEquals(2, messages.size)
        val user = messages[0]
        val assistant = messages[1]
        assertTrue(user is ChatMessage.User)
        assertEquals("echo job", (user as ChatMessage.User).text)
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
    fun runCaptureReturnsOutputAndWritesNoRows() = runBlocking {
        if (isWindows) return@runBlocking // `echo`/`sh` argv form is POSIX-only.

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalAgentDatabase.Schema.create(driver)
        val db = LocalAgentDatabase(driver)
        val jobs = SqlDelightJobRepository(db.jobsQueries, LocalChangeBus())
        val conversations = SqlDelightConversationRepository(db.conversationsQueries, NoOpTelemetryCounters)
        val job = oneShotEchoJob()
        jobs.create(job)

        // PR #88 — the keyword(s) override the saved argument and the output is
        // captured WITHOUT recording any conversation / run / last-run rows.
        val result = JobExecutor(jobs = jobs, conversations = conversations)
            .runCapture(job, "hello inline")

        assertIs<InlineJobResult.Output>(result)
        assertEquals("hello inline", result.text)
        assertNull(jobs.get("job-echo")!!.lastRunStatus, "inline run must not record last-run")
        assertTrue(jobs.runsForJob("job-echo").isEmpty(), "inline run must not insert a run row")
        assertNull(jobs.get("job-echo")!!.lastRunConversationId, "inline run must not create a conversation")
    }

    @Test
    fun runCaptureCancellationUnwindsPromptlyAndDestroysProcess() = runBlocking {
        if (isWindows) return@runBlocking // `sleep`/`sh` argv form is POSIX-only.

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalAgentDatabase.Schema.create(driver)
        val db = LocalAgentDatabase(driver)
        val jobs = SqlDelightJobRepository(db.jobsQueries, LocalChangeBus())
        val conversations = SqlDelightConversationRepository(db.conversationsQueries, NoOpTelemetryCounters)

        // A long-sleeping command; cancellation must break the wait cooperatively
        // (via ensureActive between poll chunks) instead of blocking for the full
        // sleep, and the finally must destroy the spawned process.
        val job = oneShotEchoJob(command = "sleep", prompt = "30")
        val executor = JobExecutor(jobs = jobs, conversations = conversations)

        var outcome: Result<InlineJobResult>? = null
        val runJob = launch(Dispatchers.IO) { outcome = runCatching { executor.runCapture(job, "30") } }
        delay(400) // let the subprocess start + the wait loop enter a poll
        val elapsed = kotlin.system.measureTimeMillis { runJob.cancelAndJoin() }

        // Cooperative cancel: cancelAndJoin returns well within the 30s sleep
        // (one poll interval), proving runCapture didn't block to completion.
        assertTrue(elapsed < 2_000, "cancellation did not unwind promptly (${elapsed}ms)")
        // The cancel propagated into runCapture (its finally destroyed the process).
        assertTrue(
            outcome == null || outcome.exceptionOrNull() is CancellationException,
            "expected CancellationException, got $outcome",
        )
    }

    private fun oneShotEchoJob(command: String = "echo", prompt: String = "hello from job") = Job(
        id = "job-echo",
        name = "echo job",
        command = command,
        prompt = prompt,
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

    @Test
    fun injectsManifestArgsAndRunsInProgramDirectory() = runBlocking {
        if (isWindows) return@runBlocking // sh script + chmod is POSIX-only.

        // A program folder holding a job.settings.json (hidden args) and a script
        // that echoes its cwd + every argument it receives.
        val dir = File(System.getProperty("java.io.tmpdir"), "jobexec-${System.nanoTime()}").apply { mkdirs() }
        try {
            File(dir, "job.settings.json").writeText(
                """{ "program": { "linux": "run.sh", "macos": "run.sh" }, "args": ["--headless", "--mode=fast"] }""",
            )
            val script = File(dir, "run.sh").apply {
                writeText("#!/bin/sh\necho \"cwd=\$(pwd)\"\nfor a in \"\$@\"; do echo \"arg:\$a\"; done\n")
                setExecutable(true)
            }

            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            LocalAgentDatabase.Schema.create(driver)
            val db = LocalAgentDatabase(driver)
            val jobs = SqlDelightJobRepository(db.jobsQueries, LocalChangeBus())
            val conversations = SqlDelightConversationRepository(db.conversationsQueries, NoOpTelemetryCounters)

            val job = Job(
                id = "job-args", name = "args job", command = script.absolutePath, prompt = "K7M 2T6",
                workingDir = null, // unset → executor derives it from the command's parent dir
                scheduleType = JobScheduleType.ONE_SHOT, cronExpression = null, fireAtEpochMs = 1,
                paused = false, createdAtEpochMs = 1, updatedAtEpochMs = 1, deletedAtEpochMs = null,
                lastRunStatus = null, lastRunAtEpochMs = null, lastRunSummary = null, lastRunConversationId = null,
            )
            jobs.create(job)
            JobExecutor(jobs = jobs, conversations = conversations).execute(job)

            val updated = jobs.get("job-args")!!
            assertEquals(JobRunStatus.SUCCEEDED, updated.lastRunStatus)
            val output = conversations.loadMessages(updated.lastRunConversationId!!)
                .filterIsInstance<ChatMessage.Assistant>().single().text

            // Ran in the program's own directory (so a .env there would be picked up).
            // `pwd` resolves symlinks, so compare against canonicalPath.
            assertTrue("cwd=${dir.canonicalPath}" in output, "ran in program dir; got: $output")
            // …received the hidden manifest args, AND the user keyword, each as a distinct arg.
            assertTrue("arg:--headless" in output, "got: $output")
            assertTrue("arg:--mode=fast" in output, "got: $output")
            assertTrue("arg:K7M 2T6" in output, "keyword passed as one arg; got: $output")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun successiveRunsContinueTheSameConversationThread() = runBlocking {
        if (isWindows) return@runBlocking

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalAgentDatabase.Schema.create(driver)
        val db = LocalAgentDatabase(driver)
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
