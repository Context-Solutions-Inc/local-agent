package com.contextsolutions.localagent.job

import com.contextsolutions.localagent.agent.ChatMessage
import com.contextsolutions.localagent.conversation.ConversationRepository
import com.contextsolutions.localagent.platform.AgentClock
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
        // The conversation's user turn is the job NAME (the human-readable label),
        // not the Command Argument (job.prompt) — the prompt is the command's input,
        // the name is what reads sensibly in the chat thread (PR #84).
        conversations.appendMessage(convId, ChatMessage.User(job.name), startedAt)
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
        // Surface RUNNING on the denormalized (synced) job row so the Jobs list shows
        // a working indicator on BOTH desktop (reactive flow) and mobile (via sync)
        // while the subprocess runs; the terminal recordLastRun below overwrites it.
        jobs.recordLastRun(
            id = job.id,
            status = JobRunStatus.RUNNING,
            atEpochMs = startedAt,
            summary = null,
            conversationId = convId,
            nowEpochMs = startedAt,
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

    /**
     * Run [job] inline for the chat agent (PR #88): spawn the subprocess with
     * [keywords] overriding the saved argument, capture its output, and return it
     * — writing NO conversation / run / last-run rows, so the inline run never
     * touches the job's own thread. **Cancellation-aware** (invariant #29/#59):
     * the wait polls in short chunks and calls [ensureActive] between polls, so
     * cancelling the calling coroutine (the chat Cancel button) breaks out and the
     * `finally` destroys the subprocess promptly — a plain blocking `waitFor`
     * would leave the process running.
     */
    suspend fun runCapture(job: Job, keywords: String): InlineJobResult = withContext(ioDispatcher) {
        val rp = startProcess(job.copy(prompt = keywords))
        try {
            val deadline = clock.nowEpochMs() + timeoutSeconds * 1_000
            while (true) {
                ensureActive() // throws CancellationException → finally kills the process
                if (rp.proc.waitFor(POLL_MS, TimeUnit.MILLISECONDS)) break
                if (clock.nowEpochMs() >= deadline) {
                    rp.proc.destroyForcibly()
                    rp.reader.join(1_000)
                    return@withContext InlineJobResult.Failure(
                        rp.output() + "\n(timed out after ${timeoutSeconds}s)",
                    )
                }
            }
            rp.reader.join(1_000)
            val exit = rp.proc.exitValue()
            val output = rp.output().ifBlank { "(no output)" }
            if (exit == 0) InlineJobResult.Output(output) else InlineJobResult.Failure(output)
        } finally {
            // Cancellation or an unexpected throw must not orphan the subprocess.
            if (rp.proc.isAlive) {
                rp.proc.destroyForcibly()
                rp.reader.join(1_000)
            }
        }
    }

    private fun runProcess(job: Job): ProcessResult {
        val rp = startProcess(job)
        val finished = rp.proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            rp.proc.destroyForcibly()
            rp.reader.join(1_000)
            return ProcessResult(exitCode = null, output = rp.output() + "\n(timed out after ${timeoutSeconds}s)")
        }
        rp.reader.join(1_000)
        return ProcessResult(exitCode = rp.proc.exitValue(), output = rp.output())
    }

    /**
     * Start the subprocess + the daemon stdout reader shared by [runProcess]
     * (scheduled runs) and [runCapture] (inline chat runs). Runs the program in
     * its OWN directory so it picks up cwd-relative state — e.g. a `.env` loaded
     * by dotenv, or other persistent files (PR #86). A program launched by
     * absolute path does NOT otherwise get its own folder as cwd. Order: an
     * explicit workingDir, else the program's parent dir (when the command is an
     * absolute path), else the user's home.
     */
    private fun startProcess(job: Job): RunningProcess {
        val workingDir = job.workingDir?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(job.command).takeIf { it.isAbsolute }?.parentFile
            ?: File(System.getProperty("user.home"))
        // Hidden CLI args from the program folder's job.settings.json (read live, so
        // they never enter the DB and never collide with the user's keyword(s)).
        val extraArgs = JobSettingsLoader.load(workingDir)?.args.orEmpty()
        val pb = ProcessBuilder(buildArgv(job.command, extraArgs, job.prompt))
            .directory(workingDir)
            .redirectErrorStream(true)
        // Convenience for commands that prefer the env var over the positional arg.
        pb.environment()["JOB_PROMPT"] = job.prompt
        // Put any privately provisioned runtime (e.g. the Node installed during init when the
        // system had none, PR #100) on PATH so the job finds `node`/`npm` at run time too.
        DesktopJobRuntimeEnv.applyTo(pb.environment())

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
        return RunningProcess(proc, buffer, reader)
    }

    private class RunningProcess(
        val proc: Process,
        private val buffer: StringBuilder,
        val reader: Thread,
    ) {
        fun output(): String = synchronized(buffer) { buffer.toString().trim() }
    }

    private data class ProcessResult(val exitCode: Int?, val output: String, val failedToStart: Boolean = false)

    internal companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 300L
        const val MAX_OUTPUT_CHARS = 8 * 1024
        const val SUMMARY_CHARS = 280

        /** Cancellation-poll granularity for [runCapture]'s wait (ms). */
        const val POLL_MS = 200L

        private val isWindows: Boolean
            get() = System.getProperty("os.name").orEmpty().lowercase().contains("win")

        /**
         * Build argv so the hidden settings [args] and the user keyword [prompt]
         * are passed as the command's trailing arguments WITHOUT being re-tokenized
         * (no shell injection): each rides a distinct positional shell parameter
         * rather than being concatenated into the script. Order is
         * `<command> [args…] <prompt>`.
         *
         * [command] is desktop-trusted (a paired peer cannot define or edit it —
         * `DesktopJobSyncPolicy` drops remote inserts/edits), so it is interpolated
         * directly on both platforms. [prompt] and [args] are attacker-influenceable
         * (the peer/LLM keyword reaches [prompt] via `RUN_JOB_INLINE`) and MUST NOT be
         * interpolated into the shell script.
         *
         * POSIX: `sh -c '<command> "$@"' sh <arg…> <prompt>` — each value rides a
         * distinct positional `$@` parameter, never re-tokenized.
         * Windows: PowerShell, with [args]/[prompt] emitted as single-quoted literals.
         * Single-quoted PowerShell strings are literal — no `$()`/`$var`/backtick
         * interpolation — and doubling any embedded `'` is the complete escape (same
         * approach as `DesktopTtsSpeaker.escapePowerShell`), so a value like
         * `$(calc)` or `'; calc; '` cannot break out of its argument.
         */
        fun buildArgv(command: String, args: List<String>, prompt: String): List<String> =
            if (isWindows) buildWindowsArgv(command, args, prompt) else buildPosixArgv(command, args, prompt)

        /** POSIX form — `args`/`prompt` ride distinct positional `$@` parameters. */
        internal fun buildPosixArgv(command: String, args: List<String>, prompt: String): List<String> =
            listOf("sh", "-c", "$command \"\$@\"", "sh") + args + prompt

        /** Windows form — `args`/`prompt` emitted as PowerShell single-quoted literals. */
        internal fun buildWindowsArgv(command: String, args: List<String>, prompt: String): List<String> {
            val rendered = args.joinToString(" ") { psSingleQuote(it) }
            return listOf("powershell", "-NoProfile", "-Command", "$command $rendered ${psSingleQuote(prompt)}")
        }

        /** Wrap [s] as a PowerShell single-quoted literal (doubling embedded `'`). */
        private fun psSingleQuote(s: String): String = "'" + s.replace("'", "''") + "'"
    }
}
