package com.contextsolutions.mobileagent.search.vertical

import com.contextsolutions.mobileagent.preferences.GpsCoordinates
import com.contextsolutions.mobileagent.preferences.SiteConfig
import com.contextsolutions.mobileagent.preferences.SourceKind
import com.contextsolutions.mobileagent.preferences.UserLocation
import com.contextsolutions.mobileagent.preferences.VerticalPreferences
import com.contextsolutions.mobileagent.search.FormattedSearchPayload
import com.contextsolutions.mobileagent.search.SearchOutcome
import com.contextsolutions.mobileagent.search.SearchSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the NEWS multi-kind routing/merge (PR #47). NEWS routes
 * feed-kind sources (RSS/DWML/HTML/JSON) to [FeedAdapter] and BRAVE_SITE_FILTER
 * sources to [BraveSiteFilterAdapter], merging the results. The two children
 * are faked with a [RecordingAdapter] so the routing decisions — especially the
 * anti-pollution rule (never run the Brave side without a Brave domain) — are
 * asserted directly.
 */
class NewsKindRoutingAdapterTest {

    @Test
    fun `feed-only list never calls the brave adapter`() = runTest {
        val feed = RecordingAdapter(SearchOutcome.Success(payload("feed", "rss"), fromCache = false))
        val brave = RecordingAdapter(SearchOutcome.Success(payload("brave", "web"), fromCache = false))
        val adapter = NewsKindRoutingAdapter(feed, brave)
        val prefs = VerticalPreferences(news = listOf(rss("npr.org")))

        val outcome = adapter.fetch("headlines", prefs, location = null, gps = null)

        assertTrue(feed.called)
        assertFalse("brave side must not run without a BRAVE_SITE_FILTER source", brave.called)
        // Single success returned as-is.
        assertEquals("feed", (outcome as SearchOutcome.Success).payload.json.let { Json.parseToJsonElement(it).jsonObject["source"]!!.toString().trim('"') })
    }

    @Test
    fun `brave-only list never calls the feed adapter`() = runTest {
        val feed = RecordingAdapter(SearchOutcome.Success(payload("feed", "rss"), fromCache = false))
        val brave = RecordingAdapter(SearchOutcome.Success(payload("brave", "web"), fromCache = false))
        val adapter = NewsKindRoutingAdapter(feed, brave)
        val prefs = VerticalPreferences(news = listOf(braveSite("apnews.com")))

        val outcome = adapter.fetch("headlines", prefs, location = null, gps = null)

        assertFalse(feed.called)
        assertTrue(brave.called)
        assertEquals("brave", (outcome as SearchOutcome.Success).payload.json.let { Json.parseToJsonElement(it).jsonObject["source"]!!.toString().trim('"') })
    }

    @Test
    fun `mixed list merges both into a parseable envelope and concatenates chips`() = runTest {
        val feed = RecordingAdapter(
            SearchOutcome.Success(
                FormattedSearchPayload(
                    json = """{"subtype":"news","sources":["feed"]}""",
                    sources = listOf(source("https://npr.org/a")),
                ),
                fromCache = false,
            ),
        )
        val brave = RecordingAdapter(
            SearchOutcome.Success(
                FormattedSearchPayload(
                    json = """[{"title":"AP","url":"https://apnews.com/x"}]""",
                    sources = listOf(source("https://apnews.com/x")),
                ),
                fromCache = false,
            ),
        )
        val adapter = NewsKindRoutingAdapter(feed, brave)
        val prefs = VerticalPreferences(news = listOf(rss("npr.org"), braveSite("apnews.com")))

        val outcome = adapter.fetch("headlines", prefs, location = null, gps = null) as SearchOutcome.Success

        assertTrue(feed.called && brave.called)
        // Envelope is valid JSON with both children embedded as structured nodes.
        val env = Json.parseToJsonElement(outcome.payload.json).jsonObject
        assertEquals("news", env["subtype"]!!.toString().trim('"'))
        assertTrue(env.containsKey("feeds"))
        assertTrue(env.containsKey("web"))
        // feed.json embedded as an object, brave.json as an array — not quoted strings.
        assertTrue(outcome.payload.json.contains("\"feeds\":{"))
        assertTrue(outcome.payload.json.contains("\"web\":["))
        // Chips concatenated.
        assertEquals(2, outcome.payload.sources.size)
    }

    @Test
    fun `merge dedupes chips by url and caps at maxMergedCitations`() = runTest {
        val feed = RecordingAdapter(
            SearchOutcome.Success(
                FormattedSearchPayload("{}", listOf(source("https://npr.org/a"), source("https://shared.com/x"))),
                fromCache = false,
            ),
        )
        val brave = RecordingAdapter(
            SearchOutcome.Success(
                FormattedSearchPayload(
                    "[]",
                    listOf(source("https://shared.com/x"), source("https://apnews.com/b"), source("https://apnews.com/c")),
                ),
                fromCache = false,
            ),
        )
        val adapter = NewsKindRoutingAdapter(feed, brave, maxMergedCitations = 3)
        val prefs = VerticalPreferences(news = listOf(rss("npr.org"), braveSite("apnews.com")))

        val outcome = adapter.fetch("q", prefs, location = null, gps = null) as SearchOutcome.Success

        // shared.com/x deduped (4 distinct urls) then capped to 3.
        assertEquals(3, outcome.payload.sources.size)
        assertEquals(1, outcome.payload.sources.count { it.url == "https://shared.com/x" })
    }

    @Test
    fun `both errors surface a merged error preferring Network`() = runTest {
        val feed = RecordingAdapter(SearchOutcome.Error(SearchOutcome.ErrorKind.BadResponse, "feed boom"))
        val brave = RecordingAdapter(SearchOutcome.Error(SearchOutcome.ErrorKind.Network, "brave boom"))
        val adapter = NewsKindRoutingAdapter(feed, brave)
        val prefs = VerticalPreferences(news = listOf(rss("npr.org"), braveSite("apnews.com")))

        val outcome = adapter.fetch("q", prefs, location = null, gps = null) as SearchOutcome.Error

        assertEquals(SearchOutcome.ErrorKind.Network, outcome.kind)
        assertTrue(outcome.message.contains("feed boom"))
        assertTrue(outcome.message.contains("brave boom"))
    }

    @Test
    fun `one error one success returns the success`() = runTest {
        val feed = RecordingAdapter(SearchOutcome.Error(SearchOutcome.ErrorKind.Network, "feed boom"))
        val brave = RecordingAdapter(SearchOutcome.Success(payload("brave", "web"), fromCache = false))
        val adapter = NewsKindRoutingAdapter(feed, brave)
        val prefs = VerticalPreferences(news = listOf(rss("npr.org"), braveSite("apnews.com")))

        val outcome = adapter.fetch("q", prefs, location = null, gps = null)

        assertTrue(outcome is SearchOutcome.Success)
    }

    @Test
    fun `empty list returns a NoKey error without calling either child`() = runTest {
        val feed = RecordingAdapter(SearchOutcome.Success(payload("feed", "rss"), fromCache = false))
        val brave = RecordingAdapter(SearchOutcome.Success(payload("brave", "web"), fromCache = false))
        val adapter = NewsKindRoutingAdapter(feed, brave)
        val prefs = VerticalPreferences(news = emptyList())

        val outcome = adapter.fetch("q", prefs, location = null, gps = null)

        assertFalse(feed.called || brave.called)
        assertEquals(SearchOutcome.ErrorKind.NoKey, (outcome as SearchOutcome.Error).kind)
    }

    private fun rss(domain: String) =
        SiteConfig(domain, domain, SourceKind.RSS, "https://$domain/rss.xml")

    private fun braveSite(domain: String) =
        SiteConfig(domain, domain, SourceKind.BRAVE_SITE_FILTER, domain)

    private fun source(url: String) = SearchSource(title = url, url = url, snippet = "s")

    private fun payload(tag: String, kind: String) = FormattedSearchPayload(
        json = """{"source":"$tag","kind":"$kind"}""",
        sources = listOf(source("https://$tag.example/1")),
    )

    private class RecordingAdapter(private val result: SearchOutcome) : VerticalSearchAdapter {
        var called = false
            private set

        override suspend fun fetch(
            query: String,
            prefs: VerticalPreferences,
            location: UserLocation?,
            gps: GpsCoordinates?,
        ): SearchOutcome {
            called = true
            return result
        }
    }
}
