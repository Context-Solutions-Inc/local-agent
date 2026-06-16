package com.contextsolutions.localagent.task

import com.contextsolutions.localagent.notification.AppNotification
import com.contextsolutions.localagent.notification.NotificationPresenter
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Verifies the desktop [TaskQueue] consumer logic with a fake [TaskRunner] +
 * in-memory [TaskRepository] (docs/DESKTOP_PORT_PLAN.md, Phase 7). No model /
 * DB / GUI needed — this is the runtime check the headless CI can run for the
 * queued-task headline feature (the tray UI itself is the operator's manual
 * check). The SQL persistence is verified separately by
 * [SqlDelightTaskRepositoryTest].
 */
class TaskQueueTest {

    @Test
    fun runsQueuedTasksSequentiallyToCompletion() = runBlocking {
        val repo = FakeTaskRepository()
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val runner = TaskRunner { task, onProgress ->
            val c = concurrent.incrementAndGet()
            maxConcurrent.updateAndGet { max(it, c) }
            onProgress(0.5f)
            delay(20)
            concurrent.decrementAndGet()
            "result:${task.prompt}"
        }
        var now = 1_000L
        val queue = TaskQueue(repo, runner, NoopPresenter, nowEpochMs = { now++ })

        queue.enqueue("a")
        queue.enqueue("b")
        queue.enqueue("c")
        queue.start()

        withTimeout(5_000) {
            while (repo.snapshot().count { it.status == TaskStatus.SUCCEEDED } < 3) delay(10)
        }
        queue.stop()

        val tasks = repo.snapshot().sortedBy { it.createdAtEpochMs }
        assertEquals(3, tasks.size)
        assertTrue(tasks.all { it.status == TaskStatus.SUCCEEDED }, "all succeed")
        assertEquals(1, maxConcurrent.get(), "single-consumer: never two at once")
        assertEquals(listOf("result:a", "result:b", "result:c"), tasks.map { it.result })
        assertTrue(tasks.all { it.progress == 1f }, "succeeded tasks pinned to progress 1.0")
    }

    @Test
    fun recordsFailureWithoutStoppingTheQueue() = runBlocking {
        val repo = FakeTaskRepository()
        val runner = TaskRunner { task, _ ->
            if (task.prompt == "boom") throw IllegalStateException("kaboom")
            "ok:${task.prompt}"
        }
        var now = 1L
        val queue = TaskQueue(repo, runner, NoopPresenter, nowEpochMs = { now++ })

        queue.enqueue("boom")
        queue.enqueue("after")
        queue.start()

        withTimeout(5_000) {
            while (repo.snapshot().count { it.status.isTerminal } < 2) delay(10)
        }
        queue.stop()

        val tasks = repo.snapshot().sortedBy { it.createdAtEpochMs }
        assertEquals(TaskStatus.FAILED, tasks[0].status)
        assertEquals("kaboom", tasks[0].error)
        // The queue kept going after the failure.
        assertEquals(TaskStatus.SUCCEEDED, tasks[1].status)
        assertEquals("ok:after", tasks[1].result)
    }

    @Test
    fun cancelOfRunningTaskInterruptsAndContinues() = runBlocking {
        val repo = FakeTaskRepository()
        val started = CompletableDeferred<Unit>()
        val runner = TaskRunner { task, _ ->
            if (task.prompt == "long") {
                started.complete(Unit)
                delay(60_000) // hangs until cancelled
                "unreachable"
            } else {
                "done:${task.prompt}"
            }
        }
        var now = 1L
        val queue = TaskQueue(repo, runner, NoopPresenter, nowEpochMs = { now++ })

        val long = queue.enqueue("long")
        queue.enqueue("next")
        queue.start()

        withTimeout(5_000) { started.await() } // wait until "long" is actually running
        queue.cancel(long.id)

        withTimeout(5_000) {
            while (repo.snapshot().count { it.status.isTerminal } < 2) delay(10)
        }
        queue.stop()

        val tasks = repo.snapshot().sortedBy { it.createdAtEpochMs }
        assertEquals(TaskStatus.CANCELLED, tasks[0].status)
        assertEquals(TaskStatus.SUCCEEDED, tasks[1].status, "queue continued after cancel")
        assertEquals("done:next", tasks[1].result)
    }

    @Test
    fun cancelOfQueuedTaskSkipsIt() = runBlocking {
        val repo = FakeTaskRepository()
        val gate = CompletableDeferred<Unit>()
        val runner = TaskRunner { task, _ ->
            if (task.prompt == "first") gate.await() // hold the consumer on the first task
            "done:${task.prompt}"
        }
        var now = 1L
        val queue = TaskQueue(repo, runner, NoopPresenter, nowEpochMs = { now++ })

        queue.enqueue("first")
        val second = queue.enqueue("second")
        queue.start()

        // Cancel the still-queued second task while the first is held.
        withTimeout(5_000) {
            while (repo.get(second.id)?.status != TaskStatus.CANCELLED) {
                queue.cancel(second.id)
                delay(10)
            }
        }
        gate.complete(Unit) // release the first task

        withTimeout(5_000) {
            while (repo.snapshot().firstOrNull { it.prompt == "first" }?.status != TaskStatus.SUCCEEDED) {
                delay(10)
            }
        }
        queue.stop()

        val tasks = repo.snapshot()
        assertEquals(TaskStatus.SUCCEEDED, tasks.first { it.prompt == "first" }.status)
        assertEquals(TaskStatus.CANCELLED, tasks.first { it.prompt == "second" }.status)
    }
}

private object NoopPresenter : NotificationPresenter {
    override fun present(notification: AppNotification) = Unit
    override fun dismiss(id: String) = Unit
}

/** Thread-safe in-memory [TaskRepository] for the queue-logic test. */
private class FakeTaskRepository : TaskRepository {
    private val lock = Any()
    private val rows = LinkedHashMap<String, QueuedTask>()
    private val state = MutableStateFlow<List<QueuedTask>>(emptyList())

    override fun flow(): StateFlow<List<QueuedTask>> = state.asStateFlow()

    override suspend fun snapshot(): List<QueuedTask> = synchronized(lock) { rows.values.toList() }

    override suspend fun get(id: String): QueuedTask? = synchronized(lock) { rows[id] }

    override suspend fun enqueue(
        id: String,
        prompt: String,
        attachments: List<String>,
        nowEpochMs: Long,
    ): QueuedTask = mutate {
        val t = QueuedTask(id, prompt, TaskStatus.QUEUED, 0f, nowEpochMs, nowEpochMs, attachments = attachments)
        rows[id] = t
        t
    }

    override suspend fun nextQueued(): QueuedTask? = synchronized(lock) {
        rows.values.filter { it.status == TaskStatus.QUEUED }.minByOrNull { it.createdAtEpochMs }
    }

    override suspend fun byStatus(status: TaskStatus): List<QueuedTask> = synchronized(lock) {
        rows.values.filter { it.status == status }.sortedBy { it.createdAtEpochMs }
    }

    override suspend fun setStatus(id: String, status: TaskStatus, nowEpochMs: Long) {
        mutate { rows[id]?.let { rows[id] = it.copy(status = status, updatedAtEpochMs = nowEpochMs) } }
    }

    override suspend fun setProgress(id: String, progress: Float, nowEpochMs: Long) {
        mutate {
            rows[id]?.let {
                rows[id] = it.copy(progress = progress.coerceIn(0f, 1f), updatedAtEpochMs = nowEpochMs)
            }
        }
    }

    override suspend fun finish(
        id: String,
        status: TaskStatus,
        result: String?,
        error: String?,
        nowEpochMs: Long,
    ) {
        mutate {
            rows[id]?.let {
                rows[id] = it.copy(
                    status = status,
                    result = result,
                    error = error,
                    progress = if (status == TaskStatus.SUCCEEDED) 1f else it.progress,
                    updatedAtEpochMs = nowEpochMs,
                )
            }
        }
    }

    override suspend fun delete(id: String): Boolean = mutate { rows.remove(id) != null }

    override suspend fun deleteFinished(): Int = mutate {
        val finished = rows.values.filter { it.status.isTerminal }.map { it.id }
        finished.forEach { rows.remove(it) }
        finished.size
    }

    private fun <T> mutate(block: () -> T): T = synchronized(lock) {
        val r = block()
        state.value = rows.values.toList()
        r
    }
}
