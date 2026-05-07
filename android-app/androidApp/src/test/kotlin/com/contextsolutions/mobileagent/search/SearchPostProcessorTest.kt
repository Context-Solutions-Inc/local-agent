package com.contextsolutions.mobileagent.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPostProcessorTest {

    @Test
    fun `keeps top three organic results`() {
        val response = brave(
            result(title = "1", url = "https://a"),
            result(title = "2", url = "https://b"),
            result(title = "3", url = "https://c"),
            result(title = "4", url = "https://d"),
        )
        val out = SearchPostProcessor.format(response)
        assertEquals(3, out.sources.size)
        assertEquals(listOf("1", "2", "3"), out.sources.map { it.title })
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
    fun `formatted json fits within 2KB even with very long snippets`() {
        val long = "lorem ipsum ".repeat(500) // ≈6 KB raw per result
        val response = brave(
            result(title = "Title 1", url = "https://example.org/1", description = long),
            result(title = "Title 2", url = "https://example.org/2", description = long),
            result(title = "Title 3", url = "https://example.org/3", description = long),
        )
        val out = SearchPostProcessor.format(response)
        val bytes = out.json.encodeToByteArray().size
        assertTrue("payload $bytes bytes should be ≤2048", bytes <= 2 * 1024)
        assertFalse("at least one source should survive the cap", out.sources.isEmpty())
    }

    @Test
    fun `empty response yields an empty payload`() {
        val out = SearchPostProcessor.format(BraveSearchResponse())
        assertTrue(out.sources.isEmpty())
        assertNotNull(out.json) // still a valid (empty array) JSON string
    }

    private fun brave(vararg results: BraveResult) =
        BraveSearchResponse(web = BraveWebResults(results = results.toList()))

    private fun result(
        title: String = "title",
        url: String = "https://example.org",
        description: String = "snippet",
    ) = BraveResult(title = title, url = url, description = description)
}
