package com.contextsolutions.mobileagent.job

import com.contextsolutions.mobileagent.agent.ChatMessage
import com.contextsolutions.mobileagent.conversation.ConversationRepository
import com.contextsolutions.mobileagent.platform.AgentClock
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs a job as a desktop subprocess (PR #70): invokes [Job.command] with
 * [Job.prompt] passed as a command argument, captures the response, and records
 * it. Each run also creates a real conversation (user = prompt, assistant =
 * response) that syncs to mobile through the existing conversation path; the run
 * is linked to the job via [JobRepository.recordLastRun] (denormalized last-run +
 * `last_run_conversation_id`).
 *
 * Subprocess shape mirrors `LlamaServerProcess`: `ProcessBuilder` +
 * `redirectErrorStream(true)` + a daemon reader draining stdout into a bounded
 * buffer, `waitFor(timeout)` → `destroyForcibly()`.
 */
class JobExecutor(
    private val jobs: JobRepository,
    private val conversations: ConversationRepository,
    private val clock: AgentClock = AgentClock(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    private val logger: (String) -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Execute [job] now. Best-effort: failures are captured into the run record
     * (status FAILED) rather than thrown, so a misbehaving command never crashes
     * the scheduler coroutine.
     */
    suspend fun execute(job: Job) = withContext(ioDispatcher) {
        val startedAt = clock.nowEpochMs()
        val runId = "jobrun-${newId()}"

        // Continue the job's existing conversation thread across runs (so a
        // re-scheduled one-shot or a recurring job accumulates its prompt/response
        // history in one place); only start a fresh thread when there isn't one yet.
        val existing = job.lastRunConversationId?.takeIf { conversations.get(it) != null }
        val convId = existing ?: "job-${newId()}".also {
            conversations.create(id = it, title = job.name, nowEpochMs = startedAt)
        }
        conversations.appendMessage(convId, ChatMessage.User(job.prompt), startedAt)
        jobs.insertRun(
            JobRun(
                id = runId,
                jobId = job.id,
                conversationId = convId,
                status = JobRunStatus.RUNNING,
                startedAtEpochMs = startedAt,
                finishedAtEpochMs = null,
                exitCode = null,
                response = null,
                error = null,
            ),
        )

        val result = runCatching { runProcess(job) }.getOrElse { t ->
            ProcessResult(exitCode = null, output = t.message ?: "execution failed", failedToStart = true)
        }
        val finishedAt = clock.nowEpochMs()
        val status = when {
            result.failedToStart -> JobRunStatus.FAILED
            result.exitCode == 0 -> JobRunStatus.SUCCEEDED
            else -> JobRunStatus.FAILED
        }
        val response = result.output.ifBlank { if (status == JobRunStatus.SUCCEEDED) "(no output)" else "(no output)" }

        // Assistant = response. Render as markdown so command output (tables, headers, bold,
        // etc.) formats in the chat bubble; the renderMarkdown flag rides the conversation sync
        // so mobile renders it formatted too.
        conversations.appendMessage(
            convId,
            ChatMessage.Assistant(text = response, renderMarkdown = true),
            finishedAt,
        )
        jobs.finishRun(
            id = runId,
            status = status,
            finishedAtEpochMs = finishedAt,
            exitCode = result.exitCode,
            response = response,
            error = if (status == JobRunStatus.FAILED) response else null,
        )
        jobs.recordLastRun(
            id = job.id,
            status = status,
            atEpochMs = finishedAt,
            summary = response.take(SUMMARY_CHARS),
            conversationId = convId,
            nowEpochMs = finishedAt,
        )
        logger("job ${job.id} (${job.name}) finished status=$status exit=${result.exitCode}")
    }

    private fun runProcess(job: Job): ProcessResult {
        val workingDir = job.workingDir?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"))
        val pb = ProcessBuilder(buildArgv(job.command, job.prompt))
            .directory(workingDir)
            .redirectErrorStream(true)
        // Convenience for commands that prefer the env var over the positional arg.
        pb.environment()["JOB_PROMPT"] = job.prompt

        val proc = pb.start()
        val buffer = StringBuilder()
        val reader = thread(isDaemon = true, name = "job-${job.id}-out") {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(buffer) {
                        if (buffer.length < MAX_OUTPUT_CHARS) buffer.append(line).append('\n')
                    }
                }
            }
        }
        val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            reader.join(1_000)
            return ProcessResult(exitCode = null, output = synchronized(buffer) { buffer.toString().trim() } + "\n(timed out after ${timeoutSeconds}s)")
        }
        reader.join(1_000)
        return ProcessResult(exitCode = proc.exitValue(), output = synchronized(buffer) { buffer.toString().trim() })
    }

    private data class ProcessResult(val exitCode: Int?, val output: String, val failedToStart: Boolean = false)

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 300L
        const val MAX_OUTPUT_CHARS = 8 * 1024
        const val SUMMARY_CHARS = 280

        private val isWindows: Boolean
            get() = System.getProperty("os.name").orEmpty().lowercase().contains("win")

        /**
         * Build argv so the prompt is passed as the command's trailing argument
         * WITHOUT being re-tokenized (no shell injection): the prompt is bound to
         * a positional shell parameter rather than concatenated into the script.
         * POSIX: `sh -c '<command> "$1"' sh <prompt>`. Windows: PowerShell, prompt
         * appended as a quoted argument (best-effort).
         */
        fun buildArgv(command: String, prompt: String): List<String> = if (isWindows) {
            listOf("powershell", "-NoProfile", "-Command", "$command \"$prompt\"")
        } else {
            listOf("sh", "-c", "$command \"\$1\"", "sh", prompt)
        }
    }
}
