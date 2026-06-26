package com.contextsolutions.localagent.mylist

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.contextsolutions.localagent.db.MyListQueries
import com.contextsolutions.localagent.sync.LocalChangeBus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/**
 * SQLDelight-backed [MyListRepository]. Every mutation runs on [ioDispatcher]
 * (default `Dispatchers.IO`).
 *
 * [flow] is a **SQLDelight reactive query** (`asFlow().mapToList`), so it re-emits
 * on every write to the `mylist` table on this driver — critically including the
 * sync apply-from-peer path, which writes `upsertMyListFromPeer` directly on
 * [MyListQueries] (NOT through this repo). A synced item must light up the other
 * device's list live; a manually-seeded StateFlow would stay stale until restart
 * (invariant #51).
 *
 * Genuine-local writes also notify [bus] so [com.contextsolutions.localagent.sync.SyncController]
 * pushes them; the raw `*FromPeer` path deliberately does NOT fire the bus (no echo).
 * Deletes are soft (tombstone) so they propagate over sync instead of resurrecting.
 */
class SqlDelightMyListRepository(
    private val queries: MyListQueries,
    private val bus: LocalChangeBus,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : MyListRepository {

    override fun flow(): Flow<List<MyListItem>> =
        queries.selectAll().asFlow().mapToList(ioDispatcher).map { rows -> rows.map(::rowToItem) }

    override suspend fun snapshot(): List<MyListItem> = withContext(ioDispatcher) {
        queries.selectAll().executeAsList().map(::rowToItem)
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
        bus.notifyChanged()
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
        bus.notifyChanged()
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
        bus.notifyChanged()
        queries.selectById(id).executeAsOneOrNull()?.let(::rowToItem)
    }

    override suspend fun delete(id: String): Boolean = withContext(ioDispatcher) {
        val existed = queries.selectById(id).executeAsOneOrNull() != null
        if (existed) {
            val ts = now()
            queries.softDeleteItem(nowEpochMs = ts, id = id)
            bus.notifyChanged()
        }
        existed
    }

    override suspend fun deleteCompleted(): Int = withContext(ioDispatcher) {
        val before = queries.selectAll().executeAsList().count { it.completed == 1L }
        if (before > 0) {
            queries.softDeleteCompleted(nowEpochMs = now())
            bus.notifyChanged()
        }
        before
    }

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
