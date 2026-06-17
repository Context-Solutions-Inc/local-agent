package com.contextsolutions.localagent.mylist

import com.contextsolutions.localagent.db.MyListQueries
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * SQLDelight-backed [MyListRepository]. Every mutation runs on [ioDispatcher]
 * (default `Dispatchers.IO`) and republishes the full ordered list through
 * [flow] so the management screen and chat handler see the change live.
 *
 * The StateFlow is seeded synchronously at construction. SQLDelight's
 * Android/JDBC drivers run queries on the calling thread, and the `mylist`
 * table stays small (the UX caps practical use well under a few hundred
 * rows), so this initial read is bounded; a lazy seed flickered the
 * management screen with an empty list before the first IO-thread refresh.
 */
class SqlDelightMyListRepository(
    private val queries: MyListQueries,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MyListRepository {

    private val state: MutableStateFlow<List<MyListItem>> = MutableStateFlow(readAllOrdered())

    override fun flow(): StateFlow<List<MyListItem>> = state.asStateFlow()

    override suspend fun snapshot(): List<MyListItem> = withContext(ioDispatcher) {
        readAllOrdered()
    }

    override suspend fun snapshotActive(): List<MyListItem> = withContext(ioDispatcher) {
        queries.selectActive().executeAsList().map(::rowToItem)
    }

    override suspend fun get(id: String): MyListItem? = withContext(ioDispatcher) {
        queries.selectById(id).executeAsOneOrNull()?.let(::rowToItem)
    }

    override suspend fun create(
        id: String,
        title: String,
        priority: MyListItemPriority,
        dueDateEpochMs: Long?,
        notes: String?,
        nowEpochMs: Long,
    ): MyListItem = withContext(ioDispatcher) {
        queries.insertItem(
            id = id,
            title = title,
            priority = priority.name,
            due_date_epoch_ms = dueDateEpochMs,
            created_at_epoch_ms = nowEpochMs,
            updated_at_epoch_ms = nowEpochMs,
            notes = notes,
        )
        state.value = readAllOrdered()
        MyListItem(
            id = id,
            title = title,
            priority = priority,
            dueDateEpochMs = dueDateEpochMs,
            completed = false,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
            notes = notes,
        )
    }

    override suspend fun update(item: MyListItem, nowEpochMs: Long): MyListItem? = withContext(ioDispatcher) {
        val existing = queries.selectById(item.id).executeAsOneOrNull() ?: return@withContext null
        queries.update(
            title = item.title,
            priority = item.priority.name,
            due_date_epoch_ms = item.dueDateEpochMs,
            notes = item.notes,
            updated_at_epoch_ms = nowEpochMs,
            id = item.id,
        )
        state.value = readAllOrdered()
        item.copy(
            createdAtEpochMs = existing.created_at_epoch_ms,
            updatedAtEpochMs = nowEpochMs,
            completed = existing.completed == 1L,
        )
    }

    override suspend fun setCompleted(
        id: String,
        completed: Boolean,
        nowEpochMs: Long,
    ): MyListItem? = withContext(ioDispatcher) {
        if (queries.selectById(id).executeAsOneOrNull() == null) return@withContext null
        queries.setCompleted(
            completed = if (completed) 1L else 0L,
            updated_at_epoch_ms = nowEpochMs,
            id = id,
        )
        state.value = readAllOrdered()
        queries.selectById(id).executeAsOneOrNull()?.let(::rowToItem)
    }

    override suspend fun delete(id: String): Boolean = withContext(ioDispatcher) {
        val existed = queries.selectById(id).executeAsOneOrNull() != null
        if (existed) {
            queries.deleteById(id)
            state.value = readAllOrdered()
        }
        existed
    }

    override suspend fun deleteCompleted(): Int = withContext(ioDispatcher) {
        val before = queries.selectAll().executeAsList().count { it.completed == 1L }
        if (before > 0) {
            queries.deleteCompleted()
            state.value = readAllOrdered()
        }
        before
    }

    private fun readAllOrdered(): List<MyListItem> =
        queries.selectAll().executeAsList().map(::rowToItem)

    private fun rowToItem(row: com.contextsolutions.localagent.db.Mylist): MyListItem = MyListItem(
        id = row.id,
        title = row.title,
        priority = parsePriority(row.priority),
        dueDateEpochMs = row.due_date_epoch_ms,
        completed = row.completed == 1L,
        createdAtEpochMs = row.created_at_epoch_ms,
        updatedAtEpochMs = row.updated_at_epoch_ms,
        notes = row.notes,
    )

    private fun parsePriority(raw: String): MyListItemPriority =
        runCatching { MyListItemPriority.valueOf(raw.uppercase()) }.getOrDefault(MyListItemPriority.MEDIUM)
}
