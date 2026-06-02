package com.contextsolutions.mobileagent.task

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.contextsolutions.mobileagent.db.MobileAgentDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Exercises [SqlDelightTaskRepository] against a real in-memory SQLite DB
 * (docs/DESKTOP_PORT_PLAN.md, Phase 7). This verifies the `Tasks.sq` queries and
 * the v8 schema (migration `7.sqm`) end-to-end — `Schema.create` here builds the
 * exact schema the migration must converge to (`verifyMigrations` gate). Calls
 * are sequential (no concurrency) so the single JDBC connection isn't contended.
 */
class SqlDelightTaskRepositoryTest {

    private fun newRepo(): SqlDelightTaskRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MobileAgentDatabase.Schema.create(driver)
        return SqlDelightTaskRepository(MobileAgentDatabase(driver).tasksQueries)
    }

    @Test
    fun enqueueThenLifecycleTransitions() = runBlocking {
        val repo = newRepo()

        val t = repo.enqueue("task-1", "summarise the doc", attachments = emptyList(), nowEpochMs = 100)
        assertEquals(TaskStatus.QUEUED, t.status)
        assertEquals(0f, t.progress)
        assertEquals(t, repo.nextQueued())

        repo.setStatus("task-1", TaskStatus.RUNNING, nowEpochMs = 110)
        assertNull(repo.nextQueued(), "RUNNING task is no longer queued")
        assertEquals(listOf("task-1"), repo.byStatus(TaskStatus.RUNNING).map { it.id })

        repo.setProgress("task-1", 0.42f, nowEpochMs = 120)
        assertEquals(0.42f, repo.get("task-1")!!.progress)

        repo.finish("task-1", TaskStatus.SUCCEEDED, result = "the summary", error = null, nowEpochMs = 130)
        val done = repo.get("task-1")!!
        assertEquals(TaskStatus.SUCCEEDED, done.status)
        assertEquals("the summary", done.result)
        assertEquals(1f, done.progress, "finish pins succeeded progress to 1.0")
    }

    @Test
    fun nextQueuedReturnsOldestFirst() = runBlocking {
        val repo = newRepo()
        repo.enqueue("b", "second", emptyList(), nowEpochMs = 200)
        repo.enqueue("a", "first", emptyList(), nowEpochMs = 100)
        repo.enqueue("c", "third", emptyList(), nowEpochMs = 300)
        assertEquals("a", repo.nextQueued()!!.id)
    }

    @Test
    fun attachmentsRoundTripThroughJsonColumn() = runBlocking {
        val repo = newRepo()
        val attachments = listOf("/tmp/a.jpg", "/tmp/b.jpg")
        repo.enqueue("img", "describe these", attachments, nowEpochMs = 1)
        assertEquals(attachments, repo.get("img")!!.attachments)

        // Empty attachments persist as NULL and read back as an empty list.
        repo.enqueue("txt", "plain", emptyList(), nowEpochMs = 2)
        assertTrue(repo.get("txt")!!.attachments.isEmpty())
    }

    @Test
    fun deleteFinishedClearsOnlyTerminalRows() = runBlocking {
        val repo = newRepo()
        repo.enqueue("q", "queued", emptyList(), nowEpochMs = 1)
        repo.enqueue("ok", "done", emptyList(), nowEpochMs = 2)
        repo.finish("ok", TaskStatus.SUCCEEDED, "r", null, nowEpochMs = 3)
        repo.enqueue("bad", "boom", emptyList(), nowEpochMs = 4)
        repo.finish("bad", TaskStatus.FAILED, null, "err", nowEpochMs = 5)

        val removed = repo.deleteFinished()
        assertEquals(2, removed)
        assertEquals(listOf("q"), repo.snapshot().map { it.id })
    }

    @Test
    fun finishFailedPreservesError() = runBlocking {
        val repo = newRepo()
        repo.enqueue("x", "p", emptyList(), nowEpochMs = 1)
        repo.setProgress("x", 0.3f, nowEpochMs = 2)
        repo.finish("x", TaskStatus.FAILED, result = null, error = "nope", nowEpochMs = 3)
        val row = repo.get("x")!!
        assertEquals(TaskStatus.FAILED, row.status)
        assertEquals("nope", row.error)
        // Only SUCCEEDED is pinned to 1.0; non-success finishes settle at 0.0.
        assertEquals(0f, row.progress)
    }
}
