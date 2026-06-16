package com.contextsolutions.localagent.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmContextPostProcessorTest {

    @Test
    fun `maps one source per grounding url and joins snippets`() {
        val out = LlmContextPostProcessor.format(
            response(
                entry(
                    url = "https://espn.com/golf",
                    title = "Masters",
                    snippets = listOf("Scottie Scheffler won.", "Final score -11."),
                ),
            ),
        )
        val source = out.sources.single()
        assertEquals("https://espn.com/golf", source.url)
        assertEquals("Masters", source.title)
        assertEquals("Scottie Scheffler won.\nFinal score -11.", source.snippet)
    }

    @Test
    fun `populates age preferring the relative form`() {
        val out = LlmContextPostProcessor.format(
            BraveLlmContextResponse(
                grounding = BraveGrounding(
                    generic = listOf(entry(url = "https://a", title = "A", snippets = listOf("x"))),
                ),
                sources = mapOf(
                    "https://a" to BraveLlmSource(
                        title = "A",
                        hostname = "a",
                        age = listOf("Monday, January 15, 2024", "2024-01-15", "2 hours ago"),
                    ),
                ),
            ),
        )
        assertEquals("2 hours ago", out.sources.single().age)
    }

    @Test
    fun `strips html and collapses whitespace in snippets`() {
        val out = LlmContextPostProcessor.format(
            response(entry(url = "https://a", title = "A", snippets = listOf("The <b>Eagles</b>   won\n28-22."))),
        )
        assertEquals("The Eagles won 28-22.", out.sources.single().snippet)
    }

    @Test
    fun `drops entries with blank url or title and empty-snippet sources`() {
        val out = LlmContextPostProcessor.format(
            response(
                entry(url = "", title = "no url", snippets = listOf("x")),
                entry(url = "https://b", title = "", snippets = listOf("x")),
                entry(url = "https://c", title = "empty", snippets = emptyList()),
                entry(url = "https://d", title = "kept", snippets = listOf("real content")),
            ),
        )
        assertEquals(listOf("https://d"), out.sources.map { it.url })
    }

    @Test
    fun `enforces the byte budget by trimming then dropping`() {
        // Six sources each with a huge snippet — must be bounded well under any
        // pathological size and shorter than the input.
        val huge = "y".repeat(4000)
        val out = LlmContextPostProcessor.format(
            response(
                *(1..6).map { entry(url = "https://s$it", title = "S$it", snippets = listOf(huge)) }.toTypedArray(),
            ),
        )
        assertTrue("payload should be bounded", out.json.encodeToByteArray().size <= 6 * 1024)
        assertTrue("at least one source survives", out.sources.isNotEmpty())
        assertTrue(
            "surviving snippets are trimmed below the raw size",
            out.sources.all { it.snippet.length < huge.length },
        )
    }

    @Test
    fun `strips json-blob snippet chunks, keeping prose`() {
        val out = LlmContextPostProcessor.format(
            response(
                entry(
                    url = "https://espn.com/scores",
                    title = "Scores",
                    snippets = listOf(
                        "Game 7: Cavaliers 114, Raptors 102",
                        "{\"@type\":\"VideoObject\",\"contentUrl\":\"https://x/097aa94d.mp4\",\"duration\":\"PT0M55S\"}",
                        "  [{\"title\":\"NBA Standings\",\"table\":[[\"1z --DET\"]]}]",
                    ),
                ),
            ),
        )
        // Only the prose chunk survives; the VideoObject and table JSON are gone.
        assertEquals("Game 7: Cavaliers 114, Raptors 102", out.sources.single().snippet)
    }

    @Test
    fun `drops an entry whose snippets are all json`() {
        val out = LlmContextPostProcessor.format(
            response(
                entry(url = "https://a", title = "JSON only", snippets = listOf("{\"x\":1}")),
                entry(url = "https://b", title = "kept", snippets = listOf("Pistons 111, Cavaliers 101")),
            ),
        )
        assertEquals(listOf("https://b"), out.sources.map { it.url })
    }

    @Test
    fun `empty grounding yields no sources`() {
        val out = LlmContextPostProcessor.format(BraveLlmContextResponse())
        assertTrue(out.sources.isEmpty())
        assertEquals("[]", out.json)
    }

    @Test
    fun `omits age when sources map has no entry`() {
        val out = LlmContextPostProcessor.format(
            response(entry(url = "https://a", title = "A", snippets = listOf("x"))),
        )
        assertNull(out.sources.single().age)
    }

    private fun entry(url: String, title: String, snippets: List<String>) =
        BraveGroundingEntry(url = url, title = title, snippets = snippets)

    private fun response(vararg entries: BraveGroundingEntry) =
        BraveLlmContextResponse(grounding = BraveGrounding(generic = entries.toList()))
}
