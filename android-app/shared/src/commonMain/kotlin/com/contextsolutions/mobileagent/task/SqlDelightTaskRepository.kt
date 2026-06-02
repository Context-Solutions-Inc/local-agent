package com.contextsolutions.mobileagent.task

import com.contextsolutions.mobileagent.db.TasksQueries
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * SQLDelight-backed [TaskRepository] (docs/DESKTOP_PORT_PLAN.md, Phase 7).
 * Mirrors [com.contextsolutions.mobileagent.todo.SqlDelightTodoRepository]: a
 * [MutableStateFlow] seeded synchronously at construction, mutations on
 * [ioDispatcher], full ordered list republished on every change so the tray/UI
 * sees progress live.
 *
 * [QueuedTask.attachments] is stored as a JSON array string in the `attachments`
 * TEXT column (null/absent → empty list). `progress` is stored as REAL (Double)
 * and narrowed to Float at the boundary.
 */
class SqlDelightTaskRepository(
    private val queries: TasksQueries,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TaskRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val attachmentsSerializer = ListSerializer(String.serializer())

    private val state: MutableStateFlow<List<QueuedTask>> = MutableStateFlow(readAll())

    override fun flow(): StateFlow<List<QueuedTask>> = state.asStateFlow()

    override suspend fun snapshot(): List<QueuedTask> = withContext(ioDispatcher) { readAll() }

    override suspend fun get(id: String): QueuedTask? = withContext(ioDispatcher) {
        queries.selectById(id).executeAsOneOrNull()?.let(::rowToTask)
    }

    override suspend fun enqueue(
        id: String,
        prompt: String,
        attachments: List<String>,
        nowEpochMs: Long,
    ): QueuedTask = withContext(ioDispatcher) {
        queries.insertTask(
            id = id,
            prompt = prompt,
            status = TaskStatus.QUEUED.name,
            progress = 0.0,
            created_at_epoch_ms = nowEpochMs,
            updated_at_epoch_ms = nowEpochMs,
            result = null,
            error = null,
            attachments = encodeAttachments(attachments),
        )
        state.value = readAll()
        QueuedTask(
            id = id,
            prompt = prompt,
            status = TaskStatus.QUEUED,
            progress = 0f,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
            attachments = attachments,
        )
    }

    override suspend fun nextQueued(): QueuedTask? = withContext(ioDispatcher) {
        queries.selectNextQueued().executeAsOneOrNull()?.let(::rowToTask)
    }

    override suspend fun byStatus(status: TaskStatus): List<QueuedTask> = withContext(ioDispatcher) {
        queries.selectByStatus(status.name).executeAsList().map(::rowToTask)
    }

    override suspend fun setStatus(id: String, status: TaskStatus, nowEpochMs: Long) {
        withContext(ioDispatcher) {
            queries.updateStatus(status = status.name, updated_at_epoch_ms = nowEpochMs, id = id)
            state.value = readAll()
        }
    }

    override suspend fun setProgress(id: String, progress: Float, nowEpochMs: Long) {
        withContext(ioDispatcher) {
            queries.updateProgress(
                progress = progress.coerceIn(0f, 1f).toDouble(),
                updated_at_epoch_ms = nowEpochMs,
                id = id,
            )
            state.value = readAll()
        }
    }

    override suspend fun finish(
        id: String,
        status: TaskStatus,
        result: String?,
        error: String?,
        nowEpochMs: Long,
    ) {
        withContext(ioDispatcher) {
            queries.finish(
                status = status.name,
                result = result,
                error = error,
                progress = if (status == TaskStatus.SUCCEEDED) 1.0 else 0.0,
                updated_at_epoch_ms = nowEpochMs,
                id = id,
            )
            state.value = readAll()
        }
    }

    override suspend fun delete(id: String): Boolean = withContext(ioDispatcher) {
        val existed = queries.selectById(id).executeAsOneOrNull() != null
        if (existed) {
            queries.deleteById(id)
            state.value = readAll()
        }
        existed
    }

    override suspend fun deleteFinished(): Int = withContext(ioDispatcher) {
        val before = queries.selectAll().executeAsList().count { TaskStatus.valueOf(it.status).isTerminal }
        if (before > 0) {
            queries.deleteFinished()
            state.value = readAll()
        }
        before
    }

    private fun readAll(): List<QueuedTask> =
        queries.selectAll().executeAsList().map(::rowToTask)

    private fun rowToTask(row: com.contextsolutions.mobileagent.db.Tasks): QueuedTask = QueuedTask(
        id = row.id,
        prompt = row.prompt,
        status = parseStatus(row.status),
        progress = row.progress.toFloat(),
        createdAtEpochMs = row.created_at_epoch_ms,
        updatedAtEpochMs = row.updated_at_epoch_ms,
        result = row.result,
        error = row.error,
        attachments = decodeAttachments(row.attachments),
    )

    private fun parseStatus(raw: String): TaskStatus =
        runCatching { TaskStatus.valueOf(raw.uppercase()) }.getOrDefault(TaskStatus.QUEUED)

    private fun encodeAttachments(list: List<String>): String? =
        if (list.isEmpty()) null else json.encodeToString(attachmentsSerializer, list)

    private fun decodeAttachments(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(attachmentsSerializer, raw) }.getOrDefault(emptyList())
    }
}
