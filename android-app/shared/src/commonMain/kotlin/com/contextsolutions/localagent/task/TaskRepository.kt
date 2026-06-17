package com.contextsolutions.localagent.task

import kotlinx.coroutines.flow.StateFlow

/**
 * Persistence for the queued agent-task system (docs/DESKTOP_PORT_PLAN.md,
 * Phase 7). Backed by the `tasks` table (SQLDelight). The single-consumer
 * [TaskQueue] drives it; the tray/UI observes [flow].
 *
 * In `commonMain` (with the SQLDelight impl) so a future Android task queue can
 * reuse it. Mirrors [com.contextsolutions.localagent.mylist.MyListRepository]'s
 * shape: a synchronously-seeded [StateFlow] republished on every mutation.
 */
interface TaskRepository {

    /** Live full list (queue order, oldest first). Re-emits on every mutation. */
    fun flow(): StateFlow<List<QueuedTask>>

    suspend fun snapshot(): List<QueuedTask>

    suspend fun get(id: String): QueuedTask?

    /** Insert a fresh [TaskStatus.QUEUED] task and return it. */
    suspend fun enqueue(
        id: String,
        prompt: String,
        attachments: List<String>,
        nowEpochMs: Long,
    ): QueuedTask

    /** Oldest still-[TaskStatus.QUEUED] task, or null when the queue is empty. */
    suspend fun nextQueued(): QueuedTask?

    /** Tasks currently in [status] (used for RUNNING crash-recovery on start). */
    suspend fun byStatus(status: TaskStatus): List<QueuedTask>

    suspend fun setStatus(id: String, status: TaskStatus, nowEpochMs: Long)

    suspend fun setProgress(id: String, progress: Float, nowEpochMs: Long)

    /** Terminal write — status + result/error + final progress in one statement. */
    suspend fun finish(
        id: String,
        status: TaskStatus,
        result: String?,
        error: String?,
        nowEpochMs: Long,
    )

    suspend fun delete(id: String): Boolean

    /** Clear finished rows (SUCCEEDED / FAILED / CANCELLED). Returns the count removed. */
    suspend fun deleteFinished(): Int
}
