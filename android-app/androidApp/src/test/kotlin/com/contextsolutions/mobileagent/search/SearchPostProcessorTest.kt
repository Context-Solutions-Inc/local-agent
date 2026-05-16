package com.contextsolutions.mobileagent.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPostProcessorTest {

    @Test
    fun `keeps top five organic results`() {
        val response = brave(
            result(title = "1", url = "https://a"),
            result(title = "2", url = "https://b"),
            result(title = "3", url = "https://c"),
            result(title = "4", url = "https://d"),
            result(title = "5", url = "https://e"),
            result(title = "6", url = "https://f"),
        )
        val out = SearchPostProcessor.format(response)
        assertEquals(5, out.sources.size)
        assertEquals(listOf("1", "2", "3", "4", "5"), out.sources.map { it.title })
    }

    @Test
    fun `truncates long snippets with an ellipsis`() {
        val long = "x".repeat(500)
        val out = SearchPostProcessor.format(brave(result(description = long)))
        val snippet = out.sources.single().snippet
        assertTrue("snippet len ${snippet.length} should be ≤201 (200 chars + ellipsis)", snippet.length <= 201)
        assertTrue("snippet should end with ellipsis", snippet.endsWith("…"))
    }

    @Test
    fun `strips html tags from descriptions`() {
        val out = SearchPostProcessor.format(
            brave(result(description = "The <strong>Eagles</strong> won 28-22 last night."))
        )
        assertEquals("The Eagles won 28-22 last night.", out.sources.single().snippet)
    }

    @Test
    fun `discards results missing url or title`() {
        val out = SearchPostProcessor.format(
            brave(
                result(title = "", url = "https://no-title"),
                result(title = "no-url", url = ""),
                result(title = "ok", url = "https://ok"),
            )
        )
        assertEquals(listOf("ok"), out.sources.map { it.title })
    }

    @Test
    fun `formatted json fits within 4KB even with very long snippets`() {
        val long = "lorem ipsum ".repeat(500) // ≈6 KB raw per result
        val response = brave(
            result(title = "Title 1", url = "https://example.org/1", description = long),
            result(title = "Title 2", url = "https://example.org/2", description = long),
            result(title = "Title 3", url = "https://example.org/3", description = long),
            result(title = "Title 4", url = "https://example.org/4", description = long),
            result(title = "Title 5", url = "https://example.org/5", description = long),
        )
        val out = SearchPostProcessor.format(response)
        val bytes = out.json.encodeToByteArray().size
        assertTrue("payload $bytes bytes should be ≤4096", bytes <= 4 * 1024)
        assertFalse("at least one source should survive the cap", out.sources.isEmpty())
    }

    @Test
    fun `empty response yields an empty payload`() {
        val out = SearchPostProcessor.format(BraveSearchResponse())
        assertTrue(out.sources.isEmpty())
        assertNotNull(out.json) // still a valid (empty array) JSON string
    }

    @Test
    fun `news-shaped query places all news ahead of web`() {
        val out = SearchPostProcessor.format(
            BraveSearchResponse(
                web = BraveWebResults(
                    results = listOf(
                        result(title = "Web 1", url = "https://w1"),
                        result(title = "Web 2", url = "https://w2"),
                        result(title = "Web 3", url = "https://w3"),
                    ),
                ),
                news = BraveNewsResults(
                    results = listOf(
                        news(title = "N1", url = "https://n1", pageAge = "2026-05-14T18:00:00"),
                        news(title = "N2", url = "https://n2", pageAge = "2026-05-14T12:00:00"),
                        news(title = "N3", url = "https://n3", pageAge = "2026-05-13T09:00:00"),
                    ),
                ),
            ),
        )
        // All 3 news (most recent first) + 2 web fill the top 5.
        assertEquals(listOf("N1", "N2", "N3", "Web 1", "Web 2"), out.sources.map { it.title })
        assertEquals(listOf<Boolean?>(null, null, null), out.sources.take(3).map { it.breaking })
    }

    @Test
    fun `breaking news beats fresher non-breaking news`() {
        val out = SearchPostProcessor.format(
            BraveSearchResponse(
                web = BraveWebResults(results = listOf(result(title = "W", url = "https://w"))),
                news = BraveNewsResults(
                    results = listOf(
                        news(title = "Old breaking", url = "https://b", pageAge = "2026-05-10T00:00:00", breaking = true),
                        news(title = "Fresh normal", url = "https://f", pageAge = "2026-05-14T18:00:00"),
                        news(title = "Stale normal", url = "https://s", pageAge = "2026-05-13T00:00:00"),
                    ),
                ),
            ),
        )
        // All 3 news first (breaking → fresher non-breaking → stale), then the lone web fills slot 4.
        assertEquals(listOf("Old breaking", "Fresh normal", "Stale normal", "W"), out.sources.map { it.title })
        assertEquals(true, out.sources.first().breaking)
        // Non-breaking source omits the field entirely (encodeDefaults=false).
        val breakingCount = "\"breaking\":true".toRegex().findAll(out.json).count()
        assertEquals("only the one breaking source should emit the field", 1, breakingCount)
    }

    @Test
    fun `news fields appear in the JSON payload`() {
        val out = SearchPostProcessor.format(
            BraveSearchResponse(
                news = BraveNewsResults(
                    results = listOf(
                        news(title = "A", url = "https://a", age = "5 hours ago", breaking = true),
                        news(title = "B", url = "https://b", age = "1 day ago"),
                        news(title = "C", url = "https://c", age = "2 days ago"),
                    ),
                ),
            ),
        )
        assertTrue("age should be serialized", out.json.contains("\"age\":\"5 hours ago\""))
        assertTrue("breaking=true should be serialized", out.json.contains("\"breaking\":true"))
    }

    @Test
    fun `under-threshold news block falls back to web-only behavior`() {
        // 2 news entries does NOT trip the news-shaped threshold of 3.
        val out = SearchPostProcessor.format(
            BraveSearchResponse(
                web = BraveWebResults(
                    results = listOf(
                        result(title = "Web 1", url = "https://w1"),
                        result(title = "Web 2", url = "https://w2"),
                        result(title = "Web 3", url = "https://w3"),
                    ),
                ),
                news = BraveNewsResults(
                    results = listOf(
                        news(title = "N1", url = "https://n1", breaking = true),
                        news(title = "N2", url = "https://n2"),
                    ),
                ),
            ),
        )
        assertEquals(listOf("Web 1", "Web 2", "Web 3"), out.sources.map { it.title })
        assertFalse("age field should not appear in web-only payload", out.json.contains("\"age\""))
        assertFalse("breaking field should not appear", out.json.contains("\"breaking\""))
    }

    @Test
    fun `dedup prevents the same article appearing in both blocks`() {
        val out = SearchPostProcessor.format(
            BraveSearchResponse(
                web = BraveWebResults(
                    results = listOf(
                        result(title = "Web dup", url = "https://shared"),
                        result(title = "Web only", url = "https://web-only"),
                    ),
                ),
                news = BraveNewsResults(
                    results = listOf(
                        news(title = "News A", url = "https://shared"),
                        news(title = "News B", url = "https://b"),
                        news(title = "News C", url = "https://c"),
                    ),
                ),
            ),
        )
        // News A wins the shared URL; all 3 news come first, then web fill skips the
        // dup and picks "Web only". Total: 4 (fewer than TOP_N=5 because no further inputs).
        assertEquals(listOf("News A", "News B", "News C", "Web only"), out.sources.map { it.title })
    }

    @Test
    fun `news-only response fills all five slots from news`() {
        val out = SearchPostProcessor.format(
            BraveSearchResponse(
                news = BraveNewsResults(
                    results = listOf(
                        news(title = "N1", url = "https://n1"),
                        news(title = "N2", url = "https://n2"),
                        news(title = "N3", url = "https://n3"),
                        news(title = "N4", url = "https://n4"),
                        news(title = "N5", url = "https://n5"),
                        news(title = "N6", url = "https://n6"),
                    ),
                ),
            ),
        )
        // All 5 slots filled from news; web is empty so the leftover-news fallback isn't exercised.
        assertEquals(5, out.sources.size)
        assertEquals(setOf("N1", "N2", "N3", "N4", "N5"), out.sources.map { it.title }.toSet())
    }

    @Test
    fun `byte cap honored when news titles and snippets are long`() {
        val long = "lorem ipsum ".repeat(500)
        val out = SearchPostProcessor.format(
            BraveSearchResponse(
                news = BraveNewsResults(
                    results = listOf(
                        news(title = "A", url = "https://a", description = long, age = "5 hours ago", breaking = true),
                        news(title = "B", url = "https://b", description = long, age = "1 day ago"),
                        news(title = "C", url = "https://c", description = long, age = "2 days ago"),
                    ),
                ),
            ),
        )
        val bytes = out.json.encodeToByteArray().size
        assertTrue("payload $bytes bytes should be ≤4096", bytes <= 4 * 1024)
        assertFalse(out.sources.isEmpty())
    }

    private fun brave(vararg results: BraveResult) =
        BraveSearchResponse(web = BraveWebResults(results = results.toList()))

    private fun result(
        title: String = "title",
        url: String = "https://example.org",
        description: String = "snippet",
    ) = BraveResult(title = title, url = url, description = description)

    private fun news(
        title: String = "title",
        url: String = "https://example.org",
        description: String = "snippet",
        age: String? = null,
        pageAge: String? = null,
        breaking: Boolean = false,
    ) = BraveNewsResult(
        title = title,
        url = url,
        description = description,
        age = age,
        pageAge = pageAge,
        breaking = breaking,
    )
}
