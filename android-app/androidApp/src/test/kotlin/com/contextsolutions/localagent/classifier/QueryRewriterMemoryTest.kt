package com.contextsolutions.localagent.classifier

import com.contextsolutions.localagent.agent.TimeContext
import com.contextsolutions.localagent.memory.Memory
import com.contextsolutions.localagent.memory.MemoryCategory
import kotlinx.datetime.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Coverage for the M5 Phase C [QueryRewriter] possessive-substitution layer.
 * The existing date/time + abort behaviour is covered by [QueryRewriterTest];
 * this file isolates the new memory-aware rule pass.
 */
class QueryRewriterMemoryTest {

    private val rewriter = QueryRewriter(
        timeContextProvider = { fixedTimeContext() },
    )

    // -- Canonical PRD §3.2.1 example ---------------------------------------

    @Test
    fun substitutes_my_team_with_preference_memory_then_resolves_dates() {
        val memories = listOf(
            preference("my favorite nfl team is the philadelphia eagles"),
        )
        val rewritten = rewriter.rewrite("did my team win last night", memories)
        assertEquals("did philadelphia eagles win 2026-05-09 evening", rewritten)
    }

    // -- Per-category coverage ----------------------------------------------

    @Test
    fun substitutes_my_company_with_professional_memory() {
        val memories = listOf(
            professional("i work at google"),
        )
        val rewritten = rewriter.rewrite("how is my company stock doing", memories)
        assertEquals("how is google stock doing", rewritten)
    }

    @Test
    fun substitutes_where_i_live_with_personal_identity_memory() {
        val memories = listOf(
            personalIdentity("i live in toronto"),
        )
        val rewritten = rewriter.rewrite("what's the weather where i live", memories)
        // After substitution + date pass: "what's the weather toronto"
        assertEquals("what's the weather toronto", rewritten)
    }

    @Test
    fun substitutes_my_dog_with_relationship_memory() {
        val memories = listOf(
            relationship("i have a dog named rex"),
        )
        val rewritten = rewriter.rewrite("when should i take my dog to the vet", memories)
        assertEquals("when should i take rex to the vet", rewritten)
    }

    // -- Failure modes ------------------------------------------------------

    @Test
    fun aborts_when_no_memory_in_matching_category() {
        val memories = listOf(
            personalIdentity("i live in toronto"),
        )
        // "my team" needs a PREFERENCE memory; only PERSONAL_IDENTITY available.
        val rewritten = rewriter.rewrite("did my team win last night", memories)
        assertNull(rewritten)
    }

    @Test
    fun aborts_when_memory_text_has_no_extractable_span() {
        val memories = listOf(
            preference("eagles eagles eagles"), // no copula → no span
        )
        val rewritten = rewriter.rewrite("did my team win", memories)
        assertNull(rewritten)
    }

    @Test
    fun aborts_when_memory_text_tail_is_too_long() {
        val memories = listOf(
            // No copula/preposition near the end; the last marker is " is "
            // very early in the sentence so the tail blows past the 5-token
            // cap.
            preference("my favorite team is the team that has won six championships consecutively"),
        )
        val rewritten = rewriter.rewrite("did my team win", memories)
        assertNull(rewritten)
    }

    @Test
    fun empty_memory_list_falls_back_to_M4_abort_behavior() {
        // No memories → existing M4 path (abort on possessives).
        val rewritten = rewriter.rewrite("did my team win", emptyList())
        assertNull(rewritten)
    }

    @Test
    fun substitutes_only_the_specific_possessive_not_unrelated_my_words() {
        val memories = listOf(
            preference("my favorite team is the eagles"),
        )
        // "my notes" is unhandled → still triggers abort on "my X" residual.
        val rewritten = rewriter.rewrite("can my team check my notes", memories)
        assertNull(rewritten)
    }

    @Test
    fun handles_multiple_possessives_independently() {
        val memories = listOf(
            preference("my favorite team is the eagles"),
            personalIdentity("i live in toronto"),
        )
        val rewritten = rewriter.rewrite(
            "did my team play near where i live",
            memories,
        )
        assertEquals("did eagles play near toronto", rewritten)
    }

    @Test
    fun picks_first_memory_in_category_when_multiple_exist() {
        // Retriever sorts by similarity DESC, so position 0 = best match.
        val memories = listOf(
            preference("my favorite nba team is the lakers"),
            preference("my favorite nfl team is the eagles"),
        )
        val rewritten = rewriter.rewrite("did my team win", memories)
        assertEquals("did lakers win", rewritten)
    }

    // -- Stability of M4 paths ----------------------------------------------

    @Test
    fun queries_without_possessives_unaffected_by_memory_argument() {
        val memories = listOf(preference("my favorite team is the eagles"))
        val rewritten = rewriter.rewrite("what's the weather today", memories)
        assertEquals("what's the weather 2026-05-10", rewritten)
    }

    @Test
    fun preserves_aborts_on_unhandled_possessives_even_with_memories() {
        val memories = listOf(preference("my favorite team is the eagles"))
        // "my notes" is not in POSSESSIVE_RULES → aborts.
        val rewritten = rewriter.rewrite("show me my notes from yesterday", memories)
        assertNull(rewritten)
    }

    // -- Helpers ------------------------------------------------------------

    private fun preference(text: String): Memory = stubMemory(text, MemoryCategory.PREFERENCE)
    private fun professional(text: String): Memory = stubMemory(text, MemoryCategory.PROFESSIONAL)
    private fun personalIdentity(text: String): Memory = stubMemory(text, MemoryCategory.PERSONAL_IDENTITY)
    private fun relationship(text: String): Memory = stubMemory(text, MemoryCategory.RELATIONSHIP)

    private fun stubMemory(text: String, category: MemoryCategory): Memory = Memory(
        id = "stub-$text",
        text = text,
        category = category,
        conversationId = null,
        createdAtEpochMs = 0L,
        lastAccessedEpochMs = 0L,
        accessCount = 0,
        embedding = FloatArray(Memory.EMBEDDING_DIM) { 0f },
        expiresAtEpochMs = null,
    )

    private fun fixedTimeContext(): TimeContext = TimeContext(
        now = LocalDateTime(2026, 5, 10, 14, 30),
        timeZoneId = "America/Toronto",
        timeZoneAbbreviation = "EDT",
        utcOffset = "-04:00",
    )
}
