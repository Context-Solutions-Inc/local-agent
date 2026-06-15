package com.contextsolutions.mobileagent.job

/**
 * Runs a job inline for the chat agent and returns its captured OUTPUT (PR #88,
 * invariant #59) — distinct from [RemoteJobRunner], whose `runNow` is a
 * fire-and-forget `Boolean` that records to the job's own conversation thread.
 *
 * An inline run is side-effect-free: it spawns the subprocess with [keywords]
 * overriding the job's saved argument, captures the text, and writes NO
 * conversation / `JobRun` / last-run rows — so it never touches or links to the
 * job's own thread. The output is fed back to the chat LLM in the current turn.
 *
 * Two bindings behind the seam: desktop runs it locally
 * ([com.contextsolutions.mobileagent.job.LocalInlineJobRunner] → `JobExecutor.runCapture`),
 * mobile runs it on the paired desktop over the relay ([RelayInlineJobRunner]).
 * Cancelling the calling coroutine kills the subprocess (desktop) or tears down
 * the relay stream → CANCEL frame → desktop kills the subprocess (mobile).
 */
interface InlineJobRunner {
    suspend fun run(id: String, keywords: String): InlineJobResult
}

/** Result of an inline job run. */
sealed interface InlineJobResult {
    /** The subprocess exited cleanly; [text] is its captured output. */
    data class Output(val text: String) : InlineJobResult

    /** The job couldn't run, timed out, or exited non-zero; [message] is user-facing. */
    data class Failure(val message: String) : InlineJobResult
}
