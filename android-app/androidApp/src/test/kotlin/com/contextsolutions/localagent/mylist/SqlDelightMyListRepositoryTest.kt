package com.contextsolutions.localagent.mylist

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.contextsolutions.localagent.db.LocalAgentDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CRUD + ordering coverage for [SqlDelightMyListRepository]. Runs against
 * an in-memory JDBC SQLite database so the schema (with PR #15's `mylist`
 * table, renamed from `todos` in PR #99) is exercised end-to-end without an
 * Android context.
 *
 * Ordering invariant under test: completed last; within each group,
 * HIGH > MEDIUM > LOW; within each priority, dated rows precede undated;
 * within each dated group, soonest-due first. Mutations republish the
 * StateFlow so subscribers see the changes.
 */
class SqlDelightMyListRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: LocalAgentDatabase
    private lateinit var repo: SqlDelightMyListRepository

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalAgentDatabase.Schema.create(driver)
        db = LocalAgentDatabase(driver)
        repo = SqlDelightMyListRepository(db.myListQueries, com.contextsolutions.localagent.sync.LocalChangeBus(), ioDispatcher = Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun create_and_get_round_trip() = runTest {
        val item = repo.create(
            id = "t1",
            title = "buy milk",
            priority = MyListItemPriority.MEDIUM,
            dueDateEpochMs = null,
            notes = null,
            nowEpochMs = 1_000L,
        )
        assertEquals("buy milk", item.title)
        assertFalse(item.completed)
        assertEquals(item, repo.get("t1"))
    }

    @Test
    fun selectAll_sinks_completed_to_bottom_and_orders_by_priority() = runTest {
        repo.create("low", "low item", MyListItemPriority.LOW, null, null, 100L)
        repo.create("high", "high item", MyListItemPriority.HIGH, null, null, 100L)
        repo.create("med", "med item", MyListItemPriority.MEDIUM, null, null, 100L)
        repo.create("done-high", "done high", MyListItemPriority.HIGH, null, null, 100L)
        repo.setCompleted("done-high", completed = true, nowEpochMs = 110L)

        val ordered = repo.snapshot()
        assertEquals(listOf("high", "med", "low", "done-high"), ordered.map { it.id })
    }

    @Test
    fun selectAll_dated_precede_undated_within_same_priority() = runTest {
        repo.create("a", "a", MyListItemPriority.MEDIUM, dueDateEpochMs = null, notes = null, nowEpochMs = 100L)
        repo.create("b", "b", MyListItemPriority.MEDIUM, dueDateEpochMs = 500L, notes = null, nowEpochMs = 100L)
        repo.create("c", "c", MyListItemPriority.MEDIUM, dueDateEpochMs = 200L, notes = null, nowEpochMs = 100L)

        val ordered = repo.snapshot()
        // c (due=200) before b (due=500) before a (undated).
        assertEquals(listOf("c", "b", "a"), ordered.map { it.id })
    }

    @Test
    fun snapshotActive_filters_completed() = runTest {
        repo.create("a", "a", MyListItemPriority.LOW, null, null, 100L)
        repo.create("b", "b", MyListItemPriority.LOW, null, null, 100L)
        repo.setCompleted("a", completed = true, nowEpochMs = 110L)

        assertEquals(listOf("b"), repo.snapshotActive().map { it.id })
    }

    @Test
    fun update_changes_fields_but_preserves_completed_and_createdAt() = runTest {
        val original = repo.create("t1", "old", MyListItemPriority.LOW, null, null, 100L)
        repo.setCompleted("t1", completed = true, nowEpochMs = 110L)

        val edited = repo.update(
            original.copy(
                title = "new",
                priority = MyListItemPriority.HIGH,
                dueDateEpochMs = 999L,
                notes = "annot",
            ),
            nowEpochMs = 120L,
        )
        assertNotNull(edited)
        // update() preserves completed; the dedicated setCompleted path
        // is the only mutator that flips it.
        assertTrue(edited!!.completed)
        assertEquals("new", edited.title)
        assertEquals(MyListItemPriority.HIGH, edited.priority)
        assertEquals(999L, edited.dueDateEpochMs)
        assertEquals("annot", edited.notes)
        assertEquals(100L, edited.createdAtEpochMs)
        assertEquals(120L, edited.updatedAtEpochMs)
    }

    @Test
    fun setCompleted_unknown_id_returns_null() = runTest {
        assertNull(repo.setCompleted("ghost", true, 100L))
    }

    @Test
    fun delete_returns_true_only_on_actual_row() = runTest {
        repo.create("t1", "a", MyListItemPriority.MEDIUM, null, null, 100L)
        assertTrue(repo.delete("t1"))
        assertFalse(repo.delete("t1"))
    }

    @Test
    fun deleteCompleted_returns_count_and_leaves_active() = runTest {
        repo.create("a", "a", MyListItemPriority.LOW, null, null, 100L)
        repo.create("b", "b", MyListItemPriority.LOW, null, null, 100L)
        repo.create("c", "c", MyListItemPriority.LOW, null, null, 100L)
        repo.setCompleted("a", true, 110L)
        repo.setCompleted("c", true, 110L)

        val deleted = repo.deleteCompleted()
        assertEquals(2, deleted)
        assertEquals(listOf("b"), repo.snapshot().map { it.id })
    }

    @Test
    fun snapshot_reflects_each_mutation() = runTest {
        repo.create("a", "a", MyListItemPriority.LOW, null, null, 100L)
        assertEquals(listOf("a"), repo.snapshot().map { it.id })
        repo.create("b", "b", MyListItemPriority.HIGH, null, null, 100L)
        assertEquals(listOf("b", "a"), repo.snapshot().map { it.id })
        repo.setCompleted("b", true, 110L)
        assertEquals(listOf("a", "b"), repo.snapshot().map { it.id })
    }
}
