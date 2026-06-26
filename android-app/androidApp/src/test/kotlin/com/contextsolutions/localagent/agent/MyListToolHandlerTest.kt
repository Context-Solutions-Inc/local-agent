package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.inference.PendingToolCall
import com.contextsolutions.localagent.mylist.SqlDelightMyListRepository
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
 * Contract test for [MyListToolHandler]. Verifies JSON-in / JSON-out shapes
 * for all six tools, the index-reference cache invalidation policy, and
 * the error envelope on resolution failures.
 */
class MyListToolHandlerTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: LocalAgentDatabase
    private lateinit var repo: SqlDelightMyListRepository
    private lateinit var handler: MyListToolHandler

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
        repo = SqlDelightMyListRepository(db.myListQueries, com.contextsolutions.localagent.sync.LocalChangeBus(), ioDispatcher = Dispatchers.Unconfined)
        handler = MyListToolHandler(repo, fakeClock)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    private fun call(name: String, args: String): JsonObject = runBlocking {
        json.parseToJsonElement(handler.execute(PendingToolCall(name, args))).jsonObject
    }

    @Test
    fun `add_mylist_item with required title returns ok envelope`() {
        val obj = call("add_mylist_item", """{"title": "buy milk"}""")
        assertEquals("ok", obj["status"]!!.jsonPrimitive.content)
        assertEquals("buy milk", obj["title"]!!.jsonPrimitive.content)
        assertEquals("MEDIUM", obj["priority"]!!.jsonPrimitive.content)
    }

    @Test
    fun `add_mylist_item missing title errors`() {
        val obj = call("add_mylist_item", "{}")
        assertEquals("error", obj["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `show_mylist returns indexed rows and caches ids`() {
        call("add_mylist_item", """{"title": "a", "priority": "HIGH"}""")
        call("add_mylist_item", """{"title": "b", "priority": "LOW"}""")
        val obj = call("show_mylist", "{}")
        assertEquals("ok", obj["status"]!!.jsonPrimitive.content)
        assertEquals("2", obj["count"]!!.jsonPrimitive.content)
        val rows = obj["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, rows.size)
        // High-priority "a" comes first.
        assertEquals("a", rows[0]["title"]!!.jsonPrimitive.content)
        assertEquals("1", rows[0]["index"]!!.jsonPrimitive.content)
        // Now an index-ref should resolve via the cache.
        val complete = call("complete_mylist_item", """{"index": 1}""")
        assertEquals("ok", complete["status"]!!.jsonPrimitive.content)
        assertEquals("a", complete["title"]!!.jsonPrimitive.content)
        assertTrue(complete["completed"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `show_mylist with an index returns only that single item`() {
        call("add_mylist_item", """{"title": "a", "priority": "HIGH"}""")
        call("add_mylist_item", """{"title": "b", "priority": "LOW", "notes": "second"}""")
        // Sorted active list: "a" (HIGH) is #1, "b" (LOW) is #2.
        val obj = call("show_mylist", """{"index": 2}""")
        assertEquals("ok", obj["status"]!!.jsonPrimitive.content)
        assertEquals("1", obj["count"]!!.jsonPrimitive.content)
        assertTrue(obj["single"]!!.jsonPrimitive.boolean)
        val rows = obj["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, rows.size)
        assertEquals("b", rows[0]["title"]!!.jsonPrimitive.content)
        assertEquals("2", rows[0]["index"]!!.jsonPrimitive.content)
        assertEquals("second", rows[0]["notes"]!!.jsonPrimitive.content)
    }

    @Test
    fun `show_mylist with an out-of-range index errors`() {
        call("add_mylist_item", """{"title": "only"}""")
        val obj = call("show_mylist", """{"index": 5}""")
        assertEquals("error", obj["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `complete_mylist_item with stale index errors instead of guessing`() {
        call("add_mylist_item", """{"title": "a"}""")
        call("show_mylist", "{}")
        // Add a new row — mutating invalidates the cached index list.
        call("add_mylist_item", """{"title": "b"}""")
        // Index #1 is now ambiguous; the cache cleared, so resolution fails.
        val obj = call("complete_mylist_item", """{"index": 1}""")
        assertEquals("error", obj["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `complete_mylist_item by title substring resolves a single active match`() {
        call("add_mylist_item", """{"title": "buy milk"}""")
        call("add_mylist_item", """{"title": "call mom"}""")
        val obj = call("complete_mylist_item", """{"title_substring": "milk", "completed": true}""")
        assertEquals("ok", obj["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `complete_mylist_item ambiguous title substring refuses to guess`() {
        call("add_mylist_item", """{"title": "buy milk"}""")
        call("add_mylist_item", """{"title": "buy oat milk"}""")
        val obj = call("complete_mylist_item", """{"title_substring": "milk"}""")
        assertEquals("error", obj["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `delete_mylist_item by id removes the row`() {
        val added = call("add_mylist_item", """{"title": "a"}""")
        val id = added["id"]!!.jsonPrimitive.content
        val obj = call("delete_mylist_item", """{"id": "$id"}""")
        assertEquals("ok", obj["status"]!!.jsonPrimitive.content)
        // Round-trip with a list to confirm.
        val after = call("show_mylist", "{}")
        assertEquals("0", after["count"]!!.jsonPrimitive.content)
    }

    @Test
    fun `edit_mylist_item patches priority leaving other fields intact`() {
        val added = call("add_mylist_item", """{"title": "buy milk"}""")
        val id = added["id"]!!.jsonPrimitive.content
        val obj = call("edit_mylist_item", """{"id": "$id", "priority": "HIGH"}""")
        assertEquals("ok", obj["status"]!!.jsonPrimitive.content)
        assertEquals("HIGH", obj["priority"]!!.jsonPrimitive.content)
        assertEquals("buy milk", obj["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `clear_completed_mylist_items returns the deleted count`() {
        call("add_mylist_item", """{"title": "a"}""")
        call("add_mylist_item", """{"title": "b"}""")
        call("show_mylist", "{}")
        call("complete_mylist_item", """{"index": 1}""")
        // Re-list before the next mutation re-arms the cache.
        call("show_mylist", """{"include_completed": true}""")
        val obj = call("clear_completed_mylist_items", "{}")
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
        assertTrue(names.contains("add_mylist_item"))
        assertTrue(names.contains("show_mylist"))
        assertTrue(names.contains("complete_mylist_item"))
        assertTrue(names.contains("delete_mylist_item"))
        assertTrue(names.contains("edit_mylist_item"))
        assertTrue(names.contains("clear_completed_mylist_items"))
    }
}
