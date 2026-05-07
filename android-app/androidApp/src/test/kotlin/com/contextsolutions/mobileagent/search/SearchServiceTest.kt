package com.contextsolutions.mobileagent.search

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.contextsolutions.mobileagent.db.MobileAgentDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SearchServiceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: MobileAgentDatabase
    private lateinit var dao: SearchCacheDao
    private lateinit var fakeClient: FakeBraveSearchClient
    private var nowEpochMs: Long = 1_000L

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MobileAgentDatabase.Schema.create(driver)
        db = MobileAgentDatabase(driver)
        dao = SearchCacheDao(db.searchCacheQueries, nowEpochMs = { nowEpochMs })
        fakeClient = FakeBraveSearchClient()
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `returns NoKey error when no key configured`() = runTest {
        val service = service(keyProvider = StaticBraveKeyProvider(null))

        val outcome = service.search("eagles score") as SearchOutcome.Error
        assertEquals(SearchOutcome.ErrorKind.NoKey, outcome.kind)
        assertEquals(0, fakeClient.callCount) // network was not touched
    }

    @Test
    fun `returns Disabled when search toggle is off`() = runTest {
        val service = SearchService(
            keyProvider = StaticBraveKeyProvider("test-key"),
            client = fakeClient,
            cache = dao,
            isEnabled = { false },
        )
        val outcome = service.search("eagles score") as SearchOutcome.Error
        assertEquals(SearchOutcome.ErrorKind.Disabled, outcome.kind)
        assertEquals(0, fakeClient.callCount)
    }

    @Test
    fun `Disabled wins over no-key`() = runTest {
        val service = SearchService(
            keyProvider = StaticBraveKeyProvider(null),
            client = fakeClient,
            cache = dao,
            isEnabled = { false },
        )
        val outcome = service.search("query") as SearchOutcome.Error
        assertEquals(SearchOutcome.ErrorKind.Disabled, outcome.kind)
    }

    @Test
    fun `returns NoKey for blank key`() = runTest {
        val service = service(keyProvider = StaticBraveKeyProvider("   "))
        val outcome = service.search("eagles score") as SearchOutcome.Error
        assertEquals(SearchOutcome.ErrorKind.NoKey, outcome.kind)
    }

    @Test
    fun `cache miss calls client and stores success`() = runTest {
        val payload = samplePayload()
        fakeClient.next = BraveSearchResult.Success(payload)
        val service = service()

        val outcome = service.search("kotlin coroutines") as SearchOutcome.Success
        assertEquals(payload.json, outcome.payload.json)
        assertFalse(outcome.fromCache)
        assertEquals(1, fakeClient.callCount)
        assertEquals(1L, dao.count())
    }

    @Test
    fun `cache hit short-circuits the client`() = runTest {
        fakeClient.next = BraveSearchResult.Success(samplePayload())
        val service = service()

        // First call populates.
        service.search("kotlin coroutines")
        // Second call should hit cache; bump time forward but stay within general TTL.
        nowEpochMs += 1_000

        val second = service.search("kotlin coroutines") as SearchOutcome.Success
        assertTrue(second.fromCache)
        assertEquals(1, fakeClient.callCount)
    }

    @Test
    fun `network error propagates without caching`() = runTest {
        fakeClient.next = BraveSearchResult.Error(BraveSearchResult.ErrorKind.Network, "boom")
        val service = service()

        val outcome = service.search("eagles score") as SearchOutcome.Error
        assertEquals(SearchOutcome.ErrorKind.Network, outcome.kind)
        assertEquals(0L, dao.count())
    }

    @Test
    fun `auth error maps to Auth and does not cache`() = runTest {
        fakeClient.next = BraveSearchResult.Error(BraveSearchResult.ErrorKind.Auth, "401")
        val service = service()
        val outcome = service.search("eagles score") as SearchOutcome.Error
        assertEquals(SearchOutcome.ErrorKind.Auth, outcome.kind)
        assertEquals(0L, dao.count())
    }

    @Test
    fun `rate-limit error maps to RateLimited`() = runTest {
        fakeClient.next = BraveSearchResult.Error(BraveSearchResult.ErrorKind.RateLimited, "429")
        val service = service()
        val outcome = service.search("eagles score") as SearchOutcome.Error
        assertEquals(SearchOutcome.ErrorKind.RateLimited, outcome.kind)
    }

    private fun service(
        keyProvider: BraveKeyProvider = StaticBraveKeyProvider("test-key"),
    ): SearchService = SearchService(keyProvider, fakeClient, dao)

    private fun samplePayload(): FormattedSearchPayload = SearchPostProcessor.format(
        BraveSearchResponse(
            web = BraveWebResults(
                results = listOf(BraveResult(title = "ESPN", url = "https://espn.com", description = "Sample")),
            ),
        ),
    )

    private class FakeBraveSearchClient : BraveSearchClient {
        var next: BraveSearchResult = BraveSearchResult.Error(BraveSearchResult.ErrorKind.Network, "unset")
        var callCount: Int = 0
        var lastQuery: String? = null
        var lastKey: String? = null

        override suspend fun search(query: String, apiKey: String): BraveSearchResult {
            callCount++
            lastQuery = query
            lastKey = apiKey
            return next
        }
    }

    private class StaticBraveKeyProvider(private val key: String?) : BraveKeyProvider {
        override fun currentKey(): String? = key
    }
}
