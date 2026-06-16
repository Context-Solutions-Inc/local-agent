package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.inference.PendingToolCall
import com.contextsolutions.localagent.todo.SqlDelightTodoRepository
import com.contextsolutions.localagent.todo.TodoPriority
import com.contextsolutions.localagent.db.LocalAgentDatabase
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contract test for [TodoToolHandler]. Verifies JSON-in / JSON-out shapes
 * for all six tools, the index-reference cache invalidation policy, and
 * the error envelope on resolution failures.
 */
class TodoToolHandlerTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: LocalAgentDatabase
    private lateinit var repo: SqlDelightTodoRepository
    private lateinit var handler: TodoToolHandler

    private val fakeClock = object : Clock {
        var nowValue: Instant = Instant.fromEpochMilliseconds(1_000_000L)
        override fun now(): Instant = nowValue
    }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalAgentDatabase.Schema.create(driver)
        db = LocalAgentDatabase(driver)
        repo = SqlDelightTodoRepository(db.todosQueries, ioDispatcher = Dispatchers.Unconfined)
        handler = TodoToolHandler(repo, fakeClock)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    private fun call(name: String, args: String): JsonObject = runBlocking {
        json.parseToJsonElement(handler.execute(PendingToolCall(name, args))).jsonObject
    }

    @Test
    fun `add_todo with required title returns ok envelope`() {
        val obj = call("add_todo", """{"title": "buy milk"}""")
        assertEquals("ok", obj["status"]!!.jsonPrimitive.content)
        assertEquals("buy milk", obj["title"]!!.jsonPrimitive.content)
        assertEquals("MEDIUM", obj["priority"]!!.jsonPrimitive.content)
    }

    @Test
    fun `add_todo missing title errors`() {
        val obj = call("add_todo", "{}")
        assertEquals("error", obj["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `list_todos returns indexed rows and caches ids`() {
        call("add_todo", """{"title": "a", "priority": "HIGH"}""")
        call("add_todo", """{"title": "b", "priority": "LOW"}""")
        val obj = call("list_todos", "{}")
        assertEquals("ok", obj["status"]!!.jsonPrimitive.content)
        assertEquals("2", obj["count"]!!.jsonPrimitive.content)
        val rows = obj["todos"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, rows.size)
        // High-priority "a" comes first.
        assertEquals("a", rows[0]["title"]!!.jsonPrimitive.content)
        assertEquals("1", rows[0]["index"]!!.jsonPrimitive.content)
        // Now an index-ref should resolve via the cache.
        val complete = call("complete_todo", """{"index": 1}""")
        assertEquals("ok", complete["status"]!!.jsonPrimitive.content)
        assertEquals("a", complete["title"]!!.jsonPrimitive.content)
        assertTrue(complete["completed"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `complete_todo with stale index errors instead of guessing`() {
        call("add_todo", """{"title": "a"}""")
        call("list_todos", "{}")
        // Add a new row — mutating invalidates the cached index list.
        call("add_todo", """{"title": "b"}""")
        // Index #1 is now ambiguous; the cache cleared, so resolution fails.
        val obj = call("complete_todo", """{"index": 1}""")
        assertEquals("error", obj["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `complete_todo by title substring resolves a single active match`() {
        call("add_todo", """{"title": "buy milk"}""")
        call("add_todo", """{"title": "call mom"}""")
        val obj = call("complete_todo", """{"title_substring": "milk", "completed": true}""")
        assertEquals("ok", obj["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `complete_todo ambiguous title substring refuses to guess`() {
        call("add_todo", """{"title": "buy milk"}""")
        call("add_todo", """{"title": "buy oat milk"}""")
        val obj = call("complete_todo", """{"title_substring": "milk"}""")
        assertEquals("error", obj["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `delete_todo by id removes the row`() {
        val added = call("add_todo", """{"title": "a"}""")
        val id = added["id"]!!.jsonPrimitive.content
        val obj = call("delete_todo", """{"id": "$id"}""")
        assertEquals("ok", obj["status"]!!.jsonPrimitive.content)
        // Round-trip with a list to confirm.
        val after = call("list_todos", "{}")
        assertEquals("0", after["count"]!!.jsonPrimitive.content)
    }

    @Test
    fun `edit_todo patches priority leaving other fields intact`() {
        val added = call("add_todo", """{"title": "buy milk"}""")
        val id = added["id"]!!.jsonPrimitive.content
        val obj = call("edit_todo", """{"id": "$id", "priority": "HIGH"}""")
        assertEquals("ok", obj["status"]!!.jsonPrimitive.content)
        assertEquals("HIGH", obj["priority"]!!.jsonPrimitive.content)
        assertEquals("buy milk", obj["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `clear_completed_todos returns the deleted count`() {
        call("add_todo", """{"title": "a"}""")
        call("add_todo", """{"title": "b"}""")
        call("list_todos", "{}")
        call("complete_todo", """{"index": 1}""")
        // Re-list before the next mutation re-arms the cache.
        call("list_todos", """{"include_completed": true}""")
        val obj = call("clear_completed_todos", "{}")
        assertEquals("ok", obj["status"]!!.jsonPrimitive.content)
        assertEquals("1", obj["deleted_count"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unknown tool name returns error`() {
        val obj = call("not_a_tool", "{}")
        assertEquals("error", obj["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `handler advertises six tool definitions`() {
        assertEquals(6, handler.definitions.size)
        val names = handler.definitions.map { it.name }.toSet()
        assertTrue(names.contains("add_todo"))
        assertTrue(names.contains("list_todos"))
        assertTrue(names.contains("complete_todo"))
        assertTrue(names.contains("delete_todo"))
        assertTrue(names.contains("edit_todo"))
        assertTrue(names.contains("clear_completed_todos"))
    }
}
