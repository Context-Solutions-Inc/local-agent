package com.contextsolutions.localagent.todo

import com.contextsolutions.localagent.db.TodosQueries
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * SQLDelight-backed [TodoRepository]. Every mutation runs on [ioDispatcher]
 * (default `Dispatchers.IO`) and republishes the full ordered list through
 * [flow] so the management screen and chat handler see the change live.
 *
 * The StateFlow is seeded synchronously at construction. SQLDelight's
 * Android/JDBC drivers run queries on the calling thread, and the `todos`
 * table stays small (the UX caps practical use well under a few hundred
 * rows), so this initial read is bounded; a lazy seed flickered the
 * management screen with an empty list before the first IO-thread refresh.
 */
class SqlDelightTodoRepository(
    private val queries: TodosQueries,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TodoRepository {

    private val state: MutableStateFlow<List<Todo>> = MutableStateFlow(readAllOrdered())

    override fun flow(): StateFlow<List<Todo>> = state.asStateFlow()

    override suspend fun snapshot(): List<Todo> = withContext(ioDispatcher) {
        readAllOrdered()
    }

    override suspend fun snapshotActive(): List<Todo> = withContext(ioDispatcher) {
        queries.selectActive().executeAsList().map(::rowToTodo)
    }

    override suspend fun get(id: String): Todo? = withContext(ioDispatcher) {
        queries.selectById(id).executeAsOneOrNull()?.let(::rowToTodo)
    }

    override suspend fun create(
        id: String,
        title: String,
        priority: TodoPriority,
        dueDateEpochMs: Long?,
        notes: String?,
        nowEpochMs: Long,
    ): Todo = withContext(ioDispatcher) {
        queries.insertTodo(
            id = id,
            title = title,
            priority = priority.name,
            due_date_epoch_ms = dueDateEpochMs,
            created_at_epoch_ms = nowEpochMs,
            updated_at_epoch_ms = nowEpochMs,
            notes = notes,
        )
        state.value = readAllOrdered()
        Todo(
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

    override suspend fun update(todo: Todo, nowEpochMs: Long): Todo? = withContext(ioDispatcher) {
        val existing = queries.selectById(todo.id).executeAsOneOrNull() ?: return@withContext null
        queries.update(
            title = todo.title,
            priority = todo.priority.name,
            due_date_epoch_ms = todo.dueDateEpochMs,
            notes = todo.notes,
            updated_at_epoch_ms = nowEpochMs,
            id = todo.id,
        )
        state.value = readAllOrdered()
        todo.copy(
            createdAtEpochMs = existing.created_at_epoch_ms,
            updatedAtEpochMs = nowEpochMs,
            completed = existing.completed == 1L,
        )
    }

    override suspend fun setCompleted(
        id: String,
        completed: Boolean,
        nowEpochMs: Long,
    ): Todo? = withContext(ioDispatcher) {
        if (queries.selectById(id).executeAsOneOrNull() == null) return@withContext null
        queries.setCompleted(
            completed = if (completed) 1L else 0L,
            updated_at_epoch_ms = nowEpochMs,
            id = id,
        )
        state.value = readAllOrdered()
        queries.selectById(id).executeAsOneOrNull()?.let(::rowToTodo)
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

    private fun readAllOrdered(): List<Todo> =
        queries.selectAll().executeAsList().map(::rowToTodo)

    private fun rowToTodo(row: com.contextsolutions.localagent.db.Todos): Todo = Todo(
        id = row.id,
        title = row.title,
        priority = parsePriority(row.priority),
        dueDateEpochMs = row.due_date_epoch_ms,
        completed = row.completed == 1L,
        createdAtEpochMs = row.created_at_epoch_ms,
        updatedAtEpochMs = row.updated_at_epoch_ms,
        notes = row.notes,
    )

    private fun parsePriority(raw: String): TodoPriority =
        runCatching { TodoPriority.valueOf(raw.uppercase()) }.getOrDefault(TodoPriority.MEDIUM)
}
