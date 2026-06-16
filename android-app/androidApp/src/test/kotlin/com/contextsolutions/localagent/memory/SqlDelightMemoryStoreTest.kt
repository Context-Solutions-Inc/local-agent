package com.contextsolutions.localagent.memory

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.contextsolutions.localagent.db.LocalAgentDatabase
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Schema + DAO coverage for [SqlDelightMemoryStore]. Runs against an
 * in-memory JDBC SQLite database so the SQLDelight schema (`Memories.sq`)
 * is exercised end-to-end without needing an Android Context.
 *
 * Cosine math is covered by [CosineTest] / [EmbeddingBlobTest] separately;
 * here we verify that the store applies the math correctly and that the
 * persistence-layer side-effects (last_accessed bump, access_count++,
 * expiry filtering) match PRD §3.2.4.
 */
class SqlDelightMemoryStoreTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: LocalAgentDatabase
    private lateinit var store: SqlDelightMemoryStore

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalAgentDatabase.Schema.create(driver)
        db = LocalAgentDatabase(driver)
        // Use the test dispatcher rather than IO so runTest's virtual-time
        // scheduler controls timing.
        store = SqlDelightMemoryStore(db.memoriesQueries, ioDispatcher = Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    // -- Insert + retrieve --------------------------------------------------

    @Test
    fun roundtrips_a_memory_through_listAll() = runTest {
        val mem = stubMemory(id = "m1", text = "i live in toronto", x = 1f, y = 0f)
        store.insert(mem)

        val loaded = store.listAll()
        assertEquals(1, loaded.size)
        assertEquals(mem, loaded[0])
    }

    @Test
    fun retrieveTopK_returns_only_above_threshold() = runTest {
        store.insert(stubMemory("close", x = 1f, y = 0f))    // cos=1.0 to query
        store.insert(stubMemory("far",   x = 0f, y = 1f))    // cos=0.0 to query (filtered)

        val hits = store.retrieveTopK(
            queryEmbedding = unitVector(x = 1f, y = 0f),
            k = 5,
            threshold = 0.5,
            now = NOW,
        )
        assertEquals(1, hits.size)
        assertEquals("close", hits[0].memory.id)
    }

    @Test
    fun retrieveTopK_orders_by_descending_similarity() = runTest {
        store.insert(stubMemory("a", x = 0.6f, y = 0.8f))   // cos=0.6 to (1,0) → 0.6
        store.insert(stubMemory("b", x = 0.8f, y = 0.6f))   // cos=0.8 to (1,0) → 0.8
        store.insert(stubMemory("c", x = 1f,   y = 0f))     // cos=1.0

        val hits = store.retrieveTopK(unitVector(1f, 0f), k = 5, threshold = 0.0, now = NOW)
        assertEquals(listOf("c", "b", "a"), hits.map { it.memory.id })
    }

    @Test
    fun retrieveTopK_caps_at_k() = runTest {
        repeat(5) { i -> store.insert(stubMemory("m$i", x = 1f, y = 0f)) }

        val hits = store.retrieveTopK(unitVector(1f, 0f), k = 3, threshold = 0.0, now = NOW)
        assertEquals(3, hits.size)
    }

    @Test
    fun retrieveTopK_bumps_lastAccessed_and_accessCount_on_hits() = runTest {
        store.insert(
            stubMemory(id = "m", x = 1f, y = 0f, lastAccessed = 1_000L, accessCount = 0),
        )
        val hits = store.retrieveTopK(unitVector(1f, 0f), k = 1, threshold = 0.5, now = NOW)
        assertEquals(1, hits.size)
        assertEquals(NOW, hits[0].memory.lastAccessedEpochMs)
        assertEquals(1, hits[0].memory.accessCount)

        // Persisted state matches.
        val persisted = store.listAll().single()
        assertEquals(NOW, persisted.lastAccessedEpochMs)
        assertEquals(1, persisted.accessCount)
    }

    @Test
    fun retrieveTopK_filters_expired_memories() = runTest {
        store.insert(stubMemory("alive",   x = 1f, y = 0f, expiresAt = NOW + 1_000))
        store.insert(stubMemory("expired", x = 1f, y = 0f, expiresAt = NOW - 1_000))

        val hits = store.retrieveTopK(unitVector(1f, 0f), k = 5, threshold = 0.5, now = NOW)
        assertEquals(listOf("alive"), hits.map { it.memory.id })
    }

    // -- Cosine match --------------------------------------------------------

    @Test
    fun findCosineMatch_returns_null_when_nothing_clears_threshold() = runTest {
        store.insert(stubMemory("far", x = 0f, y = 1f))
        val hit = store.findCosineMatch(unitVector(1f, 0f), threshold = 0.85, now = NOW)
        assertNull(hit)
    }

    @Test
    fun findCosineMatch_returns_highest_match() = runTest {
        store.insert(stubMemory("close",  x = 1f, y = 0f))      // cos=1.0
        store.insert(stubMemory("medium", x = 0.9f, y = 0.4359f)) // ~cos=0.9

        val hit = store.findCosineMatch(unitVector(1f, 0f), threshold = 0.85, now = NOW)
        assertNotNull(hit)
        assertEquals("close", hit!!.id)
    }

    @Test
    fun deleteByCosine_removes_the_matched_row() = runTest {
        store.insert(stubMemory("victim", x = 1f, y = 0f))
        store.insert(stubMemory("survivor", x = 0f, y = 1f))

        val deleted = store.deleteByCosine(unitVector(1f, 0f), threshold = 0.85, now = NOW)
        assertNotNull(deleted)
        assertEquals("victim", deleted!!.id)

        assertEquals(listOf("survivor"), store.listAll().map { it.id })
    }

    // -- Conversation views --------------------------------------------------

    @Test
    fun listForConversation_returns_only_matching_rows_newest_first() = runTest {
        store.insert(stubMemory("c1-1", x = 1f, y = 0f, conversation = "conv-1", createdAt = 1_000))
        store.insert(stubMemory("c2-1", x = 1f, y = 0f, conversation = "conv-2", createdAt = 1_500))
        store.insert(stubMemory("c1-2", x = 1f, y = 0f, conversation = "conv-1", createdAt = 2_000))

        val rows = store.listForConversation("conv-1")
        assertEquals(listOf("c1-2", "c1-1"), rows.map { it.id })
    }

    @Test
    fun countForConversation_matches_listForConversation_size() = runTest {
        store.insert(stubMemory("c1-1", x = 1f, y = 0f, conversation = "conv-1"))
        store.insert(stubMemory("c2-1", x = 1f, y = 0f, conversation = "conv-2"))
        store.insert(stubMemory("c1-2", x = 1f, y = 0f, conversation = "conv-1"))

        assertEquals(2, store.countForConversation("conv-1"))
        assertEquals(1, store.countForConversation("conv-2"))
        assertEquals(0, store.countForConversation("missing"))
    }

    // -- Eviction primitives -------------------------------------------------

    @Test
    fun count_filters_expired() = runTest {
        store.insert(stubMemory("alive", x = 1f, y = 0f, expiresAt = NOW + 1_000))
        store.insert(stubMemory("dead",  x = 1f, y = 0f, expiresAt = NOW - 1_000))

        assertEquals(1, store.count(NOW))
    }

    // -- Clear all -----------------------------------------------------------

    @Test
    fun deleteAll_clears_the_store() = runTest {
        store.insert(stubMemory("a", x = 1f, y = 0f))
        store.insert(stubMemory("b", x = 0f, y = 1f))

        store.deleteAll()

        assertEquals(0, store.count(NOW))
        assertTrue(store.listAll().isEmpty())
    }

    // -- Helpers -------------------------------------------------------------

    /**
     * Build a 384-dim memory vector by placing weight in the first two slots
     * (so cosine math is easy to reason about) and normalising. Everything
     * else is zero — produces interpretable cosines under the L2-norm
     * convention the embedder uses.
     */
    private fun stubMemory(
        id: String,
        x: Float,
        y: Float,
        text: String = "memory $id",
        category: MemoryCategory = MemoryCategory.PREFERENCE,
        conversation: String? = "test-conv",
        createdAt: Long = NOW - 60_000,
        lastAccessed: Long = NOW - 30_000,
        accessCount: Int = 0,
        expiresAt: Long? = null,
    ): Memory = Memory(
        id = id,
        text = text,
        category = category,
        conversationId = conversation,
        createdAtEpochMs = createdAt,
        lastAccessedEpochMs = lastAccessed,
        accessCount = accessCount,
        embedding = unitVector(x, y),
        expiresAtEpochMs = expiresAt,
    )

    private fun unitVector(x: Float, y: Float): FloatArray {
        val v = FloatArray(Memory.EMBEDDING_DIM)
        val norm = sqrt((x * x + y * y).toDouble()).toFloat()
        if (norm == 0f) {
            v[0] = x
            v[1] = y
            return v
        }
        v[0] = x / norm
        v[1] = y / norm
        return v
    }

    companion object {
        private const val NOW = 100_000L
    }
}
