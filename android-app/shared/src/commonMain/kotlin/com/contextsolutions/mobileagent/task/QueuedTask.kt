package com.contextsolutions.mobileagent.task

/**
 * One unit of work in the desktop queued agent-task system (docs/DESKTOP_PORT_PLAN.md,
 * Phase 7). A task is a prompt (plus optional attachments) the user submits to run
 * in the background through a warm [com.contextsolutions.mobileagent.agent.AgentLoop]
 * session; the tray surfaces queue depth + progress and notifies on completion.
 *
 * Persisted in the `tasks` table (schema v8, migration `7.sqm`). Lives in
 * `commonMain` so an Android equivalent can later reuse the same model + table.
 *
 * [progress] is 0f..1f; meaningful only while [status] is [TaskStatus.RUNNING].
 * [result] holds the final assistant text on success; [error] the failure
 * message. [attachments] is a list of attachment refs (image file paths) for a
 * future vision-enabled task — empty for a text task.
 */
data class QueuedTask(
    val id: String,
    val prompt: String,
    val status: TaskStatus,
    val progress: Float,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val result: String? = null,
    val error: String? = null,
    val attachments: List<String> = emptyList(),
)

/** Lifecycle of a [QueuedTask]. Persisted as the enum name (TEXT) — see `Tasks.sq`. */
enum class TaskStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    /** True once the task has reached a final state and won't be picked up again. */
    val isTerminal: Boolean get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
}
