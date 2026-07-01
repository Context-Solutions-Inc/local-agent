package com.contextsolutions.localagent.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        assertTrue(out.json.encodeToByteArray().size <= 6 * 1024, "payload should be bounded")
        assertTrue(out.sources.isNotEmpty(), "at least one source survives")
        assertTrue(
            out.sources.all { it.snippet.length < huge.length },
            "surviving snippets are trimmed below the raw size",
        )
    }

    @Test
    fun `strips json-blob snippet chunks - keeping prose`() {
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
    fun `flattens a source whose snippets are all table-json to prose lines`() {
        val out = LlmContextPostProcessor.format(
            response(
                entry(
                    url = "https://en.wikipedia.org/2026_in_sports",
                    title = "2026 in sports",
                    snippets = listOf(
                        """
                        {"title":"Major sporting events 2026","table":[
                          {"Date":"1st - 5 June 2026","Place / Country":"Belgium","Competition":"Cycling - Ethias-Tour de Wallonie"},
                          {"Date":"7 June 2026","Place / Country":"Belgium","Competition":"Cycling - Brussels Cycling Classic"}
                        ]}
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        val snippet = out.sources.single().snippet
        // Source survives (would previously be dropped → whole search fails).
        assertEquals(
            "Major sporting events 2026 — 1st - 5 June 2026 — Belgium — Cycling - Ethias-Tour de Wallonie\n" +
                "Major sporting events 2026 — 7 June 2026 — Belgium — Cycling - Brussels Cycling Classic",
            snippet,
        )
        // No raw JSON braces/keys reach the model.
        assertTrue(snippet.none { it == '{' || it == '}' || it == '"' }, "no json braces survive")
    }

    @Test
    fun `still drops a non-table json blob`() {
        val out = LlmContextPostProcessor.format(
            response(
                entry(
                    url = "https://a",
                    title = "video only",
                    snippets = listOf(
                        "{\"@type\":\"VideoObject\",\"contentUrl\":\"https://x/097aa94d.mp4\",\"duration\":\"PT0M55S\"}",
                    ),
                ),
                entry(url = "https://b", title = "kept", snippets = listOf("Pistons 111, Cavaliers 101")),
            ),
        )
        assertEquals(listOf("https://b"), out.sources.map { it.url })
    }

    @Test
    fun `merges prose and table snippets in one source`() {
        val out = LlmContextPostProcessor.format(
            response(
                entry(
                    url = "https://a",
                    title = "Events",
                    snippets = listOf(
                        "Upcoming events below.",
                        "{\"caption\":\"Schedule\",\"table\":[{\"When\":\"June 7\",\"What\":\"Race\"}]}",
                    ),
                ),
            ),
        )
        assertEquals(
            "Upcoming events below.\nSchedule — June 7 — Race",
            out.sources.single().snippet,
        )
    }

    @Test
    fun `flattens a captionless table without a leading separator`() {
        val out = LlmContextPostProcessor.format(
            response(
                entry(
                    url = "https://a",
                    title = "T",
                    snippets = listOf("{\"table\":[{\"a\":\"x\",\"b\":\"y\"}]}"),
                ),
            ),
        )
        assertEquals("x — y", out.sources.single().snippet)
    }

    @Test
    fun `rescues a schema-org article-object snippet as headline — description prose`() {
        // Brave's NEWS llm/context returns each article as a JSON-stringified
        // schema.org object. Previously every such snippet was dropped (not the
        // table shape) → a news search returning only these failed with
        // "no usable results".
        val out = LlmContextPostProcessor.format(
            response(
                entry(
                    url = "https://apnews.com/article/uk-burnham",
                    title = "Burnham to set out a 10-year plan",
                    snippets = listOf(
                        """
                        {"dateModified":"2026-06-29T09:00:19Z","description":"Andy Burnham will outline a 10-year vision for good growth.","name":"Burnham to set out a 10-year plan | AP News","keywords":["Andy Burnham","United Kingdom"],"headline":"Burnham to set out a 10-year plan to transform Britain's economy"}
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        val snippet = out.sources.single().snippet
        assertEquals(
            "Burnham to set out a 10-year plan to transform Britain's economy — " +
                "Andy Burnham will outline a 10-year vision for good growth.",
            snippet,
        )
        // No raw JSON braces reach the model.
        assertTrue(snippet.none { it == '{' || it == '}' }, "no json braces survive")
    }

    @Test
    fun `article-object snippets keep a news source that would otherwise be dropped`() {
        // Reproduces the live failure: an entry whose ONLY snippet is article JSON
        // must survive so the search returns usable results instead of Error.
        val out = LlmContextPostProcessor.format(
            response(
                entry(
                    url = "https://reuters.com/world/iran",
                    title = "Iran latest",
                    snippets = listOf("""{"description":"Both sides paused strikes on Monday.","headline":"Iran talks"}"""),
                ),
            ),
        )
        assertEquals(listOf("https://reuters.com/world/iran"), out.sources.map { it.url })
        assertEquals("Iran talks — Both sides paused strikes on Monday.", out.sources.single().snippet)
    }

    @Test
    fun `article-object falls back to description then name when fields are missing`() {
        // description-only
        val descOnly = LlmContextPostProcessor.format(
            response(entry(url = "https://a", title = "A", snippets = listOf("""{"description":"Just a summary."}"""))),
        )
        assertEquals("Just a summary.", descOnly.sources.single().snippet)
        // name fallback when there's no headline/description
        val nameOnly = LlmContextPostProcessor.format(
            response(entry(url = "https://b", title = "B", snippets = listOf("""{"name":"Just a name"}"""))),
        )
        assertEquals("Just a name", nameOnly.sources.single().snippet)
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
