package com.contextsolutions.mobileagent.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Schema-level coverage for `SearchCache.sq`: TTL filtering on read, LRU bookkeeping
 * on cache hit, and over-cap eviction by least-recently-accessed. The schema lives
 * in `:shared/commonMain/sqldelight`; the test runs here because `:shared` has no
 * host-test source set wired up under AGP 9's KMP library plugin yet.
 */
class SearchCacheTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: MobileAgentDatabase
    private lateinit var queries: SearchCacheQueries

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MobileAgentDatabase.Schema.create(driver)
        db = MobileAgentDatabase(driver)
        queries = db.searchCacheQueries
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `insert seeds last_accessed equal to cached_at`() {
        insert(query = "weather toronto", cachedAt = 1_000, expiresAt = 1_000 + FIVE_MIN)

        val row = queries.selectCacheEntry("weather toronto", 1_500).executeAsOneOrNull()
        assertNotNull(row)
        assertEquals(1_000L, row!!.cached_at_epoch_ms)
        assertEquals(1_000L, row.last_accessed_at_epoch_ms)
    }

    @Test
    fun `selectCacheEntry filters expired rows`() {
        insert(query = "score eagles", cachedAt = 1_000, expiresAt = 1_000 + FIVE_MIN)

        val expiredRead = queries.selectCacheEntry("score eagles", 1_000 + FIVE_MIN).executeAsOneOrNull()
        assertNull(expiredRead)

        val freshRead = queries.selectCacheEntry("score eagles", 1_000 + FIVE_MIN - 1).executeAsOneOrNull()
        assertNotNull(freshRead)
    }

    @Test
    fun `updateLastAccessed bumps timestamp without rewriting payload`() {
        insert(query = "kotlin docs", cachedAt = 1_000, expiresAt = 1_000 + ONE_DAY)

        queries.updateLastAccessed(last_accessed_at_epoch_ms = 5_000, normalized_query = "kotlin docs")

        val row = queries.selectCacheEntry("kotlin docs", 5_000).executeAsOne()
        assertEquals(5_000L, row.last_accessed_at_epoch_ms)
        assertEquals(1_000L, row.cached_at_epoch_ms)
        assertEquals("payload-kotlin docs", row.payload_json)
    }

    @Test
    fun `evictLruWhenAboveCap removes least-recently-accessed first`() {
        insert(query = "a", cachedAt = 1_000, expiresAt = 1_000 + ONE_DAY)
        insert(query = "b", cachedAt = 2_000, expiresAt = 2_000 + ONE_DAY)
        insert(query = "c", cachedAt = 3_000, expiresAt = 3_000 + ONE_DAY)

        queries.evictLruWhenAboveCap(maxEntries = 2)

        assertEquals(2L, queries.countEntries().executeAsOne())
        assertNull(queries.selectCacheEntry("a", 4_000).executeAsOneOrNull())
        assertNotNull(queries.selectCacheEntry("b", 4_000).executeAsOneOrNull())
        assertNotNull(queries.selectCacheEntry("c", 4_000).executeAsOneOrNull())
    }

    @Test
    fun `evictLruWhenAboveCap respects updated last_accessed over insertion order`() {
        insert(query = "a", cachedAt = 1_000, expiresAt = 1_000 + ONE_DAY)
        insert(query = "b", cachedAt = 2_000, expiresAt = 2_000 + ONE_DAY)
        insert(query = "c", cachedAt = 3_000, expiresAt = 3_000 + ONE_DAY)

        // Bump 'a' so 'b' becomes the LRU candidate.
        queries.updateLastAccessed(last_accessed_at_epoch_ms = 10_000, normalized_query = "a")

        queries.evictLruWhenAboveCap(maxEntries = 2)

        assertEquals(2L, queries.countEntries().executeAsOne())
        assertNotNull(queries.selectCacheEntry("a", 11_000).executeAsOneOrNull())
        assertNull(queries.selectCacheEntry("b", 11_000).executeAsOneOrNull())
        assertNotNull(queries.selectCacheEntry("c", 11_000).executeAsOneOrNull())
    }

    @Test
    fun `evictLruWhenAboveCap is a no-op when count is at or below the cap`() {
        insert(query = "a", cachedAt = 1_000, expiresAt = 1_000 + ONE_DAY)
        insert(query = "b", cachedAt = 2_000, expiresAt = 2_000 + ONE_DAY)

        queries.evictLruWhenAboveCap(maxEntries = 5)
        assertEquals(2L, queries.countEntries().executeAsOne())

        queries.evictLruWhenAboveCap(maxEntries = 2)
        assertEquals(2L, queries.countEntries().executeAsOne())
    }

    @Test
    fun `deleteExpiredEntries clears only past-expiry rows`() {
        insert(query = "stale", cachedAt = 1_000, expiresAt = 1_000 + FIVE_MIN)
        insert(query = "fresh", cachedAt = 1_000, expiresAt = 1_000 + ONE_DAY)

        queries.deleteExpiredEntries(expires_at_epoch_ms = 1_000 + FIVE_MIN)

        assertEquals(1L, queries.countEntries().executeAsOne())
        assertNotNull(queries.selectCacheEntry("fresh", 2_000).executeAsOneOrNull())
    }

    private fun insert(query: String, cachedAt: Long, expiresAt: Long) {
        queries.insertCacheEntry(
            normalized_query = query,
            original_query = query,
            payload_json = "payload-$query",
            category = if (expiresAt - cachedAt <= FIVE_MIN) "time_sensitive" else "general",
            cached_at_epoch_ms = cachedAt,
            last_accessed_at_epoch_ms = cachedAt,
            expires_at_epoch_ms = expiresAt,
        )
    }

    companion object {
        private const val FIVE_MIN = 5 * 60 * 1_000L
        private const val ONE_DAY = 24 * 60 * 60 * 1_000L
    }
}
