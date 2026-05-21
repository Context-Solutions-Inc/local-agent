package com.contextsolutions.mobileagent.search

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.contextsolutions.mobileagent.db.MobileAgentDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SearchCacheDaoTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: MobileAgentDatabase
    private var clockNow: Long = 0L

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MobileAgentDatabase.Schema.create(driver)
        db = MobileAgentDatabase(driver)
        clockNow = 0L
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `miss when cache is empty`() {
        val dao = newDao()
        assertEquals(SearchCacheDao.CacheLookup.Miss, dao.lookup("kotlin coroutines"))
    }

    @Test
    fun `store then lookup returns hit with same payload`() {
        val dao = newDao()
        val payload = samplePayload()
        clockNow = 1_000

        dao.store("kotlin coroutines", payload)

        clockNow = 2_000
        val hit = dao.lookup("kotlin coroutines") as SearchCacheDao.CacheLookup.Hit
        assertEquals(payload.json, hit.payload.json)
        assertEquals(payload.sources, hit.payload.sources)
    }

    @Test
    fun `store normalizes the cache key`() {
        val dao = newDao()
        clockNow = 1_000
        dao.store("Kotlin   Coroutines", samplePayload())

        val hit = dao.lookup("kotlin coroutines")
        assertTrue(hit is SearchCacheDao.CacheLookup.Hit)
    }

    @Test
    fun `time-sensitive entries expire after 5 minutes`() {
        val dao = newDao()
        clockNow = 1_000
        dao.store("aapl price now", samplePayload()) // time_sensitive

        clockNow = 1_000 + 5 * 60 * 1_000
        assertEquals(SearchCacheDao.CacheLookup.Miss, dao.lookup("aapl price now"))
    }

    @Test
    fun `general entries survive past 5 minutes but expire after 1 hour`() {
        val dao = newDao()
        clockNow = 1_000
        dao.store("kotlin coroutines tutorial", samplePayload())

        clockNow = 1_000 + 6 * 60 * 1_000 // 6 minutes
        assertTrue(dao.lookup("kotlin coroutines tutorial") is SearchCacheDao.CacheLookup.Hit)

        clockNow = 1_000 + 1 * 60 * 60 * 1_000 // 1 h
        assertEquals(SearchCacheDao.CacheLookup.Miss, dao.lookup("kotlin coroutines tutorial"))
    }

    @Test
    fun `evicts oldest entry when above cap`() {
        val dao = newDao(maxEntries = 2)

        clockNow = 1_000
        dao.store("alpha", samplePayload())
        clockNow = 2_000
        dao.store("beta", samplePayload())
        clockNow = 3_000
        dao.store("gamma", samplePayload())

        // 'alpha' was first inserted and never re-accessed.
        assertEquals(2L, dao.count())
        assertEquals(SearchCacheDao.CacheLookup.Miss, dao.lookup("alpha"))
        assertTrue(dao.lookup("beta") is SearchCacheDao.CacheLookup.Hit)
        assertTrue(dao.lookup("gamma") is SearchCacheDao.CacheLookup.Hit)
    }

    @Test
    fun `lookup bumps last_accessed so subsequent eviction skips the recent entry`() {
        val dao = newDao(maxEntries = 2)

        clockNow = 1_000
        dao.store("alpha", samplePayload())
        clockNow = 2_000
        dao.store("beta", samplePayload())

        // Re-access alpha so it's no longer the LRU candidate.
        clockNow = 3_000
        assertNotNull(dao.lookup("alpha") as SearchCacheDao.CacheLookup.Hit)

        clockNow = 4_000
        dao.store("gamma", samplePayload())

        assertTrue(dao.lookup("alpha") is SearchCacheDao.CacheLookup.Hit)
        assertEquals(SearchCacheDao.CacheLookup.Miss, dao.lookup("beta"))
        assertTrue(dao.lookup("gamma") is SearchCacheDao.CacheLookup.Hit)
    }

    @Test
    fun `clear empties the cache`() {
        val dao = newDao()
        clockNow = 1_000
        dao.store("a", samplePayload())
        dao.store("b", samplePayload())
        assertEquals(2L, dao.count())

        dao.clear()
        assertEquals(0L, dao.count())
        assertEquals(SearchCacheDao.CacheLookup.Miss, dao.lookup("a"))
    }

    @Test
    fun `corrupt payload is dropped and reported as miss`() {
        val dao = newDao()
        clockNow = 1_000
        // Inject a row directly with malformed JSON, bypassing the DAO.
        db.searchCacheQueries.insertCacheEntry(
            normalized_query = "bad",
            original_query = "bad",
            payload_json = "not-json{",
            category = "general",
            cached_at_epoch_ms = clockNow,
            last_accessed_at_epoch_ms = clockNow,
            expires_at_epoch_ms = clockNow + 24 * 60 * 60 * 1_000,
        )

        assertEquals(SearchCacheDao.CacheLookup.Miss, dao.lookup("bad"))
        assertEquals(0L, dao.count()) // corrupt-row scrub kicked in
    }

    private fun newDao(maxEntries: Int = SearchCacheDao.DEFAULT_MAX_ENTRIES): SearchCacheDao =
        SearchCacheDao(
            queries = db.searchCacheQueries,
            nowEpochMs = { clockNow },
            maxEntries = maxEntries,
        )

    private fun samplePayload(): FormattedSearchPayload =
        SearchPostProcessor.format(
            BraveSearchResponse(
                web = BraveWebResults(
                    results = listOf(
                        BraveResult(title = "ESPN", url = "https://espn.com", description = "Sample"),
                    ),
                ),
            )
        )
}
