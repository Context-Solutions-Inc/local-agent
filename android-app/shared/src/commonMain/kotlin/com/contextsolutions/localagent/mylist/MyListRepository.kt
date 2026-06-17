package com.contextsolutions.localagent.mylist

import kotlinx.coroutines.flow.Flow

/**
 * Persistence + live read access for [MyListItem] rows. Both the management
 * UI and the chat-side
 * [com.contextsolutions.localagent.agent.MyListToolHandler] write through
 * this interface; the [flow] surface keeps everything in sync without a
 * separate service layer.
 *
 * Snapshot reads are suspend functions because the SQLDelight backed
 * implementation hops to [kotlinx.coroutines.Dispatchers.IO] internally.
 * Tests and Compose can collect [flow] for live updates.
 */
interface MyListRepository {

    /** Live ordered list of every item (active first, completed last). */
    fun flow(): Flow<List<MyListItem>>

    /** One-shot snapshot of [flow]. */
    suspend fun snapshot(): List<MyListItem>

    /** Active (not-completed) snapshot — used by the chat list reply. */
    suspend fun snapshotActive(): List<MyListItem>

    /** Fetch a single row by id, or null if not found. */
    suspend fun get(id: String): MyListItem?

    /**
     * Insert a new item. Caller picks the id (UUID) so the UI can reference
     * the row optimistically. Returns the persisted [MyListItem].
     */
    suspend fun create(
        id: String,
        title: String,
        priority: MyListItemPriority,
        dueDateEpochMs: Long?,
        notes: String?,
        nowEpochMs: Long,
    ): MyListItem

    /**
     * Persist a full update for [item.id]. Bumps `updated_at_epoch_ms` to
     * [nowEpochMs]. Returns the updated row, or null if no row matched the
     * id (the caller's view of the world was stale).
     */
    suspend fun update(item: MyListItem, nowEpochMs: Long): MyListItem?

    /** Flip the completed flag for [id]; bumps `updated_at_epoch_ms`. */
    suspend fun setCompleted(id: String, completed: Boolean, nowEpochMs: Long): MyListItem?

    /** Hard-delete a single item by id. Returns true when a row was removed. */
    suspend fun delete(id: String): Boolean

    /** Hard-delete every completed item. Returns the number of rows removed. */
    suspend fun deleteCompleted(): Int
}
