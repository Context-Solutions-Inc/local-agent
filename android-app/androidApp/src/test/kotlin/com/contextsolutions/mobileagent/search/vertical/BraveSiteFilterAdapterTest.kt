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
import com.contextsolutions.mobileagent.search.SearchPostProcessor
import com.contextsolutions.mobileagent.search.SearchService
import com.contextsolutions.mobileagent.search.SearchSubtype
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Locks down the SPORTS Brave `site:` rewrite (PR #34). The adapter is shared
 * with NEWS, so the NEWS case is included as a regression guard.
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

        assertEquals("who won the masters last year (site:espn.com)", fakeClient.lastQuery)
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
    fun `news still rewrites against its own site list`() = runTest {
        // Default subtype is NEWS — guards against the SPORTS change leaking.
        val adapter = BraveSiteFilterAdapter(searchService = service)
        val prefs = VerticalPreferences(news = listOf(brave("cbc.ca")))

        adapter.fetch(query = "election results", prefs = prefs, location = null)

        assertEquals("election results (site:cbc.ca)", fakeClient.lastQuery)
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
