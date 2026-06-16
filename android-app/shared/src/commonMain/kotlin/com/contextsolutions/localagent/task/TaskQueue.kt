package com.contextsolutions.localagent.task

import com.contextsolutions.localagent.notification.AppNotification
import com.contextsolutions.localagent.notification.NotificationKind
import com.contextsolutions.localagent.notification.NotificationPresenter
import kotlin.coroutines.coroutineContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Single-consumer queue that runs [QueuedTask]s sequentially through one warm
 * [TaskRunner] (docs/DESKTOP_PORT_PLAN.md, Phase 7). The desktop tray submits
 * tasks via [enqueue]; one consumer coroutine pulls the oldest QUEUED row,
 * marks it RUNNING, runs it, records the terminal state, and fires a completion
 * notification — then takes the next. Exposes [tasks] (the repository's live
 * list) for the tray's queue-depth + progress display.
 *
 * **Persistence-driven.** The durable queue is the `tasks` table, so [start]
 * first re-queues any rows left RUNNING by a previous process (crash/quit
 * recovery) and then drains whatever is already QUEUED — surviving restarts the
 * way the plan requires. A CONFLATED wakeup channel signals the idle consumer
 * when [enqueue] adds work; the consumer re-reads the table each loop, so a
 * coalesced signal never loses a task.
 *
 * **Cancellation.** [cancel] of the running task cancels the in-flight runner
 * (the consumer records CANCELLED); [cancel] of a still-queued task just flips
 * its row to CANCELLED so the consumer skips it. Scope shutdown ([stop]) is
 * distinguished from a per-task cancel by checking `scope.isActive`, so quitting
 * mid-task doesn't mis-record it as user-cancelled.
 */
@OptIn(ExperimentalUuidApi::class)
class TaskQueue(
    private val repository: TaskRepository,
    private val runner: TaskRunner,
    private val notifications: NotificationPresenter,
    private val nowEpochMs: () -> Long,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val logger: (String) -> Unit = {},
) {
    /** Live task list for the tray/UI (queue order). */
    val tasks: StateFlow<List<QueuedTask>> = repository.flow()

    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private var consumerJob: Job? = null

    // The currently-running task + its runner job, so cancel() can interrupt it.
    // Single-writer (the consumer); read by cancel() from another thread.
    @Volatile private var running: RunningRef? = null

    private class RunningRef(val id: String, val job: Job)

    /** Begin the consumer loop. Idempotent — a second call while running is a no-op. */
    fun start() {
        if (consumerJob?.isActive == true) return
        consumerJob = scope.launch {
            requeueOrphans()
            consumeLoop()
        }
    }

    fun stop() {
        consumerJob?.cancel()
        consumerJob = null
    }

    /** Submit a new task. Returns the persisted QUEUED task; wakes the consumer. */
    suspend fun enqueue(prompt: String, attachments: List<String> = emptyList()): QueuedTask {
        val task = repository.enqueue(
            id = "task-${Uuid.random()}",
            prompt = prompt,
            attachments = attachments,
            nowEpochMs = nowEpochMs(),
        )
        wakeups.trySend(Unit)
        return task
    }

    /** Cancel a task by id — interrupts it if running, else flips its queued row to CANCELLED. */
    suspend fun cancel(id: String) {
        val current = running
        if (current?.id == id) {
            current.job.cancel()
        } else {
            repository.setStatus(id, TaskStatus.CANCELLED, nowEpochMs())
        }
    }

    /** Remove finished (SUCCEEDED/FAILED/CANCELLED) rows. */
    suspend fun clearFinished(): Int = repository.deleteFinished()

    private suspend fun requeueOrphans() {
        for (orphan in repository.byStatus(TaskStatus.RUNNING)) {
            repository.setStatus(orphan.id, TaskStatus.QUEUED, nowEpochMs())
            logger("re-queued orphaned task ${orphan.id}")
        }
    }

    private suspend fun consumeLoop() {
        while (coroutineContext.isActive) {
            val next = repository.nextQueued()
            if (next == null) {
                wakeups.receive() // suspend until enqueue signals work
                continue
            }
            runOne(next)
        }
    }

    private suspend fun runOne(task: QueuedTask) {
        repository.setStatus(task.id, TaskStatus.RUNNING, nowEpochMs())
        val deferred = scope.async {
            runner.run(task) { p ->
                // Best-effort progress persistence; fire-and-forget so a slow
                // DB write never stalls the runner.
                scope.launch { repository.setProgress(task.id, p, nowEpochMs()) }
            }
        }
        running = RunningRef(task.id, deferred)
        try {
            val result = deferred.await()
            repository.finish(task.id, TaskStatus.SUCCEEDED, result = result, error = null, nowEpochMs = nowEpochMs())
            notify(task, "Task complete", preview(task.prompt))
        } catch (ce: CancellationException) {
            running = null
            if (!scope.isActive) throw ce // app shutting down — not a user cancel
            repository.finish(task.id, TaskStatus.CANCELLED, result = null, error = "cancelled", nowEpochMs = nowEpochMs())
            notify(task, "Task cancelled", preview(task.prompt))
            return
        } catch (t: Throwable) {
            repository.finish(task.id, TaskStatus.FAILED, result = null, error = t.message ?: "error", nowEpochMs = nowEpochMs())
            notify(task, "Task failed", t.message ?: preview(task.prompt))
        } finally {
            running = null
        }
    }

    private fun notify(task: QueuedTask, title: String, body: String) {
        notifications.present(
            AppNotification(id = "task:${task.id}", title = title, body = body, kind = NotificationKind.TASK),
        )
    }

    private fun preview(prompt: String): String =
        if (prompt.length <= PREVIEW_LEN) prompt else prompt.take(PREVIEW_LEN).trimEnd() + "…"

    private companion object {
        const val PREVIEW_LEN = 60
    }
}
