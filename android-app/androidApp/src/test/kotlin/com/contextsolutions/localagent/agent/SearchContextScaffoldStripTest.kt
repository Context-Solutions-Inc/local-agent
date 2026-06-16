package com.contextsolutions.localagent.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Asserts AgentLoop.stripSearchContextScaffolding — the post-processing pass
 * that removes host-internal `[SEARCH CONTEXT]` scaffolding the model parrots
 * into its own answer (PR #30), while keeping the surrounding prose. The
 * scrub is vertical-agnostic; the trigger is the marker, not the subtype.
 */
class SearchContextScaffoldStripTest {

    private fun strip(input: String): String =
        AgentLoop.stripSearchContextScaffolding(input)

    @Test
    fun `removes full fabricated block keeping only prose`() {
        val input = """
            [SEARCH CONTEXT]
            query: latest financial news 2026-05-20
            subtype: finance
            [{"title":"Markets rally","url":"https://example.com","snippet":"stocks up"}]
            Tech stocks surged today on strong earnings.
            Treasury yields eased slightly.
            [/SEARCH CONTEXT]
        """.trimIndent()
        assertEquals(
            "Tech stocks surged today on strong earnings.\nTreasury yields eased slightly.",
            strip(input),
        )
    }

    @Test
    fun `strips opening marker with no closing marker`() {
        val input = "[SEARCH CONTEXT]\nHere is what I found about the game."
        assertEquals("Here is what I found about the game.", strip(input))
    }

    @Test
    fun `leaves normal answer untouched even when it contains a query line`() {
        // No marker present -> guard holds -> the `query:` line is real prose,
        // not scaffolding, so it must survive.
        val input = "To search a DB you run a query: SELECT * FROM t;"
        assertEquals(input, strip(input))
    }

    @Test
    fun `keeps surrounding prose when markers wrap mid-text content`() {
        val input = """
            Here's the summary you asked for.
            [SEARCH CONTEXT]
            The Leafs beat the Bruins 4-2.
            [/SEARCH CONTEXT]
            Let me know if you want more detail.
        """.trimIndent()
        assertEquals(
            "Here's the summary you asked for.\n" +
                "The Leafs beat the Bruins 4-2.\n" +
                "Let me know if you want more detail.",
            strip(input),
        )
    }

    @Test
    fun `collapses blank lines left behind by removed scaffolding`() {
        val input = "Intro paragraph.\n\n[SEARCH CONTEXT]\n\nOutro paragraph."
        assertEquals("Intro paragraph.\n\nOutro paragraph.", strip(input))
    }

    @Test
    fun `is a no-op for a clean response`() {
        val input = "The weather in Toronto is 12°C and cloudy (weather.gc.ca)."
        assertEquals(input, strip(input))
    }
}
