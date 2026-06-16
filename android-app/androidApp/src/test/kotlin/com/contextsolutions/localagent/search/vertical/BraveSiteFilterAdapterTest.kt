package com.contextsolutions.localagent.search.vertical

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.contextsolutions.localagent.db.LocalAgentDatabase
import com.contextsolutions.localagent.preferences.SiteConfig
import com.contextsolutions.localagent.preferences.SourceKind
import com.contextsolutions.localagent.preferences.VerticalPreferences
import com.contextsolutions.localagent.search.BraveKeyProvider
import com.contextsolutions.localagent.search.BraveResult
import com.contextsolutions.localagent.search.BraveSearchClient
import com.contextsolutions.localagent.search.BraveSearchResponse
import com.contextsolutions.localagent.search.BraveSearchResult
import com.contextsolutions.localagent.search.BraveWebResults
import com.contextsolutions.localagent.search.FormattedSearchPayload
import com.contextsolutions.localagent.search.SearchCacheDao
import com.contextsolutions.localagent.search.SearchOutcome
import com.contextsolutions.localagent.search.SearchPostProcessor
import com.contextsolutions.localagent.search.SearchService
import com.contextsolutions.localagent.search.SearchSubtype
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Locks down the SPORTS and FINANCE (PR #35) Brave `site:` rewrites. The
 * adapter is shared with NEWS, so the NEWS case is included as a regression
 * guard. SPORTS rides this adapter against the LLM-context-backed service
 * (PR #41 dropped the `site:` pin, then restored it once the unpinned endpoint
 * proved too noisy — see `VerticalSearchDispatcherFactory`). The rewrite is
 * endpoint-agnostic, so a web-backed fake exercises it here.
 */
class BraveSiteFilterAdapterTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: LocalAgentDatabase
    private lateinit var dao: SearchCacheDao
    private lateinit var fakeClient: FakeBraveSearchClient
    private lateinit var service: SearchService

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalAgentDatabase.Schema.create(driver)
        db = LocalAgentDatabase(driver)
        dao = SearchCacheDao(db.searchCacheQueries, nowEpochMs = { 1_000L })
        fakeClient = FakeBraveSearchClient().apply { next = BraveSearchResult.Success(samplePayload()) }
        service = SearchService(StaticBraveKeyProvider("test-key"), fakeClient, dao)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `sports pins the query to its single preferred site`() = runTest {
        // PR #41: SPORTS rides this adapter against the LLM-context service,
        // pinned to one site (espn.com / tsn.ca) to keep the context clean.
        val adapter = BraveSiteFilterAdapter(
            searchService = service,
            subtype = SearchSubtype.SPORTS,
            maxDomains = 1,
            maxCitations = 1,
        )
        val prefs = VerticalPreferences(sports = listOf(brave("espn.com")))

        adapter.fetch(query = "what are the NBA scores 2026-05-21 evening", prefs = prefs, location = null)

        assertEquals(
            "what are the NBA scores 2026-05-21 evening site:espn.com",
            fakeClient.lastQuery,
        )
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
