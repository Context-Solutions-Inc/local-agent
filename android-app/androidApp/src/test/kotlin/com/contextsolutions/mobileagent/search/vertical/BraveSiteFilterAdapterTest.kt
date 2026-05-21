package com.contextsolutions.mobileagent.search.vertical

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.contextsolutions.mobileagent.db.MobileAgentDatabase
import com.contextsolutions.mobileagent.preferences.SiteConfig
import com.contextsolutions.mobileagent.preferences.SourceKind
import com.contextsolutions.mobileagent.preferences.VerticalPreferences
import com.contextsolutions.mobileagent.search.BraveKeyProvider
import com.contextsolutions.mobileagent.search.BraveResult
import com.contextsolutions.mobileagent.search.BraveSearchClient
import com.contextsolutions.mobileagent.search.BraveSearchResponse
import com.contextsolutions.mobileagent.search.BraveSearchResult
import com.contextsolutions.mobileagent.search.BraveWebResults
import com.contextsolutions.mobileagent.search.FormattedSearchPayload
import com.contextsolutions.mobileagent.search.SearchCacheDao
import com.contextsolutions.mobileagent.search.SearchOutcome
import com.contextsolutions.mobileagent.search.SearchPostProcessor
import com.contextsolutions.mobileagent.search.SearchService
import com.contextsolutions.mobileagent.search.SearchSubtype
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Locks down the SPORTS (PR #34) and FINANCE (PR #35) Brave `site:` rewrites.
 * The adapter is shared with NEWS, so the NEWS case is included as a regression
 * guard.
 */
class BraveSiteFilterAdapterTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: MobileAgentDatabase
    private lateinit var dao: SearchCacheDao
    private lateinit var fakeClient: FakeBraveSearchClient
    private lateinit var service: SearchService

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MobileAgentDatabase.Schema.create(driver)
        db = MobileAgentDatabase(driver)
        dao = SearchCacheDao(db.searchCacheQueries, nowEpochMs = { 1_000L })
        fakeClient = FakeBraveSearchClient().apply { next = BraveSearchResult.Success(samplePayload()) }
        service = SearchService(StaticBraveKeyProvider("test-key"), fakeClient, dao)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `sports rewrites query with single site filter`() = runTest {
        val adapter = BraveSiteFilterAdapter(searchService = service, subtype = SearchSubtype.SPORTS)
        val prefs = VerticalPreferences(sports = listOf(brave("espn.com")))

        adapter.fetch(query = "who won the masters last year", prefs = prefs, location = null)

        assertEquals("who won the masters last year site:espn.com", fakeClient.lastQuery)
    }

    @Test
    fun `sports ORs multiple site filters`() = runTest {
        val adapter = BraveSiteFilterAdapter(searchService = service, subtype = SearchSubtype.SPORTS)
        val prefs = VerticalPreferences(sports = listOf(brave("espn.com"), brave("cbssports.com")))

        adapter.fetch(query = "nba scores", prefs = prefs, location = null)

        assertEquals("nba scores (site:espn.com OR site:cbssports.com)", fakeClient.lastQuery)
    }

    @Test
    fun `sports ignores non-Brave entries and passes query through unfiltered`() = runTest {
        val adapter = BraveSiteFilterAdapter(searchService = service, subtype = SearchSubtype.SPORTS)
        // A stale RSS-kind entry (e.g. left over from the pre-#34 wiring) is filtered out.
        val prefs = VerticalPreferences(
            sports = listOf(SiteConfig("espn.com", "ESPN", SourceKind.RSS, "https://espn.com/rss")),
        )

        adapter.fetch(query = "who won the masters last year", prefs = prefs, location = null)

        assertEquals("who won the masters last year", fakeClient.lastQuery)
    }

    @Test
    fun `finance rewrites single-instrument query with site filter`() = runTest {
        val adapter = BraveSiteFilterAdapter(searchService = service, subtype = SearchSubtype.FINANCE)
        val prefs = VerticalPreferences(finance = listOf(brave("bloomberg.com")))

        adapter.fetch(query = "nvidia stock price", prefs = prefs, location = null)

        assertEquals("nvidia stock price site:bloomberg.com", fakeClient.lastQuery)
    }

    @Test
    fun `finance ORs multiple site filters`() = runTest {
        val adapter = BraveSiteFilterAdapter(searchService = service, subtype = SearchSubtype.FINANCE)
        val prefs = VerticalPreferences(finance = listOf(brave("finance.yahoo.com"), brave("marketwatch.com")))

        adapter.fetch(query = "tsla earnings", prefs = prefs, location = null)

        assertEquals("tsla earnings (site:finance.yahoo.com OR site:marketwatch.com)", fakeClient.lastQuery)
    }

    @Test
    fun `finance ignores stale RSS entries and passes query through unfiltered`() = runTest {
        val adapter = BraveSiteFilterAdapter(searchService = service, subtype = SearchSubtype.FINANCE)
        // A stale RSS-kind entry (left over from the pre-#35 wiring) is filtered out.
        val prefs = VerticalPreferences(
            finance = listOf(
                SiteConfig("finance.yahoo.com", "Yahoo Finance", SourceKind.RSS, "https://finance.yahoo.com/news/rssindex"),
            ),
        )

        adapter.fetch(query = "nvidia stock price", prefs = prefs, location = null)

        assertEquals("nvidia stock price", fakeClient.lastQuery)
    }

    @Test
    fun `finance caps citation chips but keeps full model context`() = runTest {
        fakeClient.next = BraveSearchResult.Success(multiSourcePayload())
        val adapter = BraveSiteFilterAdapter(
            searchService = service,
            subtype = SearchSubtype.FINANCE,
            maxCitations = 1,
        )
        val prefs = VerticalPreferences(finance = listOf(brave("finance.yahoo.com")))

        val outcome = adapter.fetch(query = "nvidia stock price", prefs = prefs, location = null)

        val success = outcome as SearchOutcome.Success
        // One chip rendered to the user...
        assertEquals(1, success.payload.sources.size)
        // ...but the model still sees all three hits as context.
        assertTrue(success.payload.json.contains("https://finance.yahoo.com/c"))
    }

    @Test
    fun `news keeps multiple citations when uncapped`() = runTest {
        // NEWS leaves maxCitations null — guards against the FINANCE/SPORTS cap leaking.
        fakeClient.next = BraveSearchResult.Success(multiSourcePayload())
        val adapter = BraveSiteFilterAdapter(searchService = service)
        val prefs = VerticalPreferences(news = listOf(brave("cbc.ca")))

        val outcome = adapter.fetch(query = "election results", prefs = prefs, location = null)

        val success = outcome as SearchOutcome.Success
        assertEquals(3, success.payload.sources.size)
    }

    @Test
    fun `news still rewrites against its own site list`() = runTest {
        // Default subtype is NEWS — guards against the SPORTS change leaking.
        val adapter = BraveSiteFilterAdapter(searchService = service)
        val prefs = VerticalPreferences(news = listOf(brave("cbc.ca")))

        adapter.fetch(query = "election results", prefs = prefs, location = null)

        assertEquals("election results site:cbc.ca", fakeClient.lastQuery)
    }

    private fun brave(domain: String) =
        SiteConfig(domain = domain, displayName = domain, kind = SourceKind.BRAVE_SITE_FILTER, endpointTemplate = domain)

    private fun samplePayload(): FormattedSearchPayload = SearchPostProcessor.format(
        BraveSearchResponse(
            web = BraveWebResults(
                results = listOf(BraveResult(title = "ESPN", url = "https://espn.com", description = "Sample")),
            ),
        ),
    )

    private fun multiSourcePayload(): FormattedSearchPayload = SearchPostProcessor.format(
        BraveSearchResponse(
            web = BraveWebResults(
                results = listOf(
                    BraveResult(title = "Yahoo Finance 1", url = "https://finance.yahoo.com/a", description = "A"),
                    BraveResult(title = "Yahoo Finance 2", url = "https://finance.yahoo.com/b", description = "B"),
                    BraveResult(title = "Yahoo Finance 3", url = "https://finance.yahoo.com/c", description = "C"),
                ),
            ),
        ),
    )

    private class FakeBraveSearchClient : BraveSearchClient {
        var next: BraveSearchResult = BraveSearchResult.Error(BraveSearchResult.ErrorKind.Network, "unset")
        var lastQuery: String? = null

        override suspend fun search(query: String, apiKey: String): BraveSearchResult {
            lastQuery = query
            return next
        }
    }

    private class StaticBraveKeyProvider(private val key: String?) : BraveKeyProvider {
        override fun currentKey(): String? = key
    }
}
