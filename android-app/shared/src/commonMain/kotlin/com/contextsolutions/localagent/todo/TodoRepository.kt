package com.contextsolutions.localagent.todo

import kotlinx.coroutines.flow.Flow

/**
 * Persistence + live read access for [Todo] rows. Both the management UI
 * and the chat-side [com.contextsolutions.localagent.agent.TodoToolHandler]
 * write through this interface; the [flow] surface keeps everything in sync
 * without a separate service layer.
 *
 * Snapshot reads are suspend functions because the SQLDelight backed
 * implementation hops to [kotlinx.coroutines.Dispatchers.IO] internally.
 * Tests and Compose can collect [flow] for live updates.
 */
interface TodoRepository {

    /** Live ordered list of every TODO (active first, completed last). */
    fun flow(): Flow<List<Todo>>

    /** One-shot snapshot of [flow]. */
    suspend fun snapshot(): List<Todo>

    /** Active (not-completed) snapshot — used by the chat list reply. */
    suspend fun snapshotActive(): List<Todo>

    /** Fetch a single row by id, or null if not found. */
    suspend fun get(id: String): Todo?

    /**
     * Insert a new TODO. Caller picks the id (UUID) so the UI can reference
     * the row optimistically. Returns the persisted [Todo].
     */
    suspend fun create(
        id: String,
        title: String,
        priority: TodoPriority,
        dueDateEpochMs: Long?,
        notes: String?,
        nowEpochMs: Long,
    ): Todo

    /**
     * Persist a full update for [todo.id]. Bumps `updated_at_epoch_ms` to
     * [nowEpochMs]. Returns the updated row, or null if no row matched the
     * id (the caller's view of the world was stale).
     */
    suspend fun update(todo: Todo, nowEpochMs: Long): Todo?

    /** Flip the completed flag for [id]; bumps `updated_at_epoch_ms`. */
    suspend fun setCompleted(id: String, completed: Boolean, nowEpochMs: Long): Todo?

    /** Hard-delete a single TODO by id. Returns true when a row was removed. */
    suspend fun delete(id: String): Boolean

    /** Hard-delete every completed TODO. Returns the number of rows removed. */
    suspend fun deleteCompleted(): Int
}
