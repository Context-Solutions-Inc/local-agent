package com.contextsolutions.mobileagent.task

/**
 * Executes one [QueuedTask] to completion (docs/DESKTOP_PORT_PLAN.md, Phase 7).
 *
 * The seam between [TaskQueue] (single-consumer, persistence, lifecycle) and the
 * heavy inference wiring. The desktop app supplies the real runner — it drives a
 * warm [com.contextsolutions.mobileagent.agent.AgentLoop] session, collecting the
 * streamed turn into a final string and reporting progress. Keeping it an
 * interface lets [TaskQueue] be unit-tested with a fake runner (no model needed)
 * and keeps the queue free of `InferenceSession`/`AgentLoopFactory` coupling.
 *
 * Contract: [run] suspends until the task is done and returns the final
 * assistant text; it throws on failure (the queue records FAILED) and is
 * cancellation-cooperative (the queue cancels it to honour a per-task cancel).
 * [onProgress] reports 0f..1f as work advances; calls are best-effort and may be
 * coalesced by the queue.
 */
fun interface TaskRunner {
    suspend fun run(task: QueuedTask, onProgress: (Float) -> Unit): String
}
