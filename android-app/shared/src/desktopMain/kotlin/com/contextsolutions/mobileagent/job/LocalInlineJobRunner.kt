package com.contextsolutions.mobileagent.job

/**
 * Desktop binding of [InlineJobRunner] (PR #88): runs the job locally via
 * [JobExecutor.runCapture], which spawns the subprocess with the chat keyword(s)
 * overriding the job's saved argument and captures its output WITHOUT writing any
 * conversation / run / last-run rows (so the inline run never touches the job's
 * own thread). Cancelling the calling coroutine destroys the subprocess (see
 * `runCapture`).
 */
class LocalInlineJobRunner(
    private val jobs: JobRepository,
    private val executor: JobExecutor,
) : InlineJobRunner {

    override suspend fun run(id: String, keywords: String): InlineJobResult {
        val job = jobs.get(id) ?: return InlineJobResult.Failure("Job not found.")
        if (job.deletedAtEpochMs != null) return InlineJobResult.Failure("That job was deleted.")
        return executor.runCapture(job, keywords)
    }
}
