package com.contextsolutions.localagent.classifier

import com.contextsolutions.localagent.agent.TimeContext
import kotlinx.datetime.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueryRewriterTest {

    // 2026-05-10 (Sunday), 14:32 EDT — same fixture used by PromptAssemblerTest.
    private val context = TimeContext(
        now = LocalDateTime(2026, 5, 10, 14, 32),
        timeZoneId = "America/Toronto",
        timeZoneAbbreviation = "EDT",
        utcOffset = "-04:00",
    )
    private val rewriter = QueryRewriter { context }

    // -- Date/time substitution --------------------------------------------

    @Test
    fun yesterday_resolves_to_iso() {
        assertEquals(
            "did the eagles win 2026-05-09",
            rewriter.rewrite("did the eagles win yesterday"),
        )
    }

    @Test
    fun last_night_resolves_before_yesterday() {
        // "last night" must match before "yesterday" rule (rule ordering check).
        assertEquals(
            "did the eagles win 2026-05-09 evening",
            rewriter.rewrite("did the eagles win last night"),
        )
    }

    @Test
    fun today_resolves_to_iso() {
        assertEquals(
            "what's the weather 2026-05-10",
            rewriter.rewrite("what's the weather today"),
        )
    }

    @Test
    fun tonight_and_this_evening_both_resolve() {
        assertEquals(
            "what time is the game 2026-05-10 evening",
            rewriter.rewrite("what time is the game tonight"),
        )
        assertEquals(
            "what time is the game 2026-05-10 evening",
            rewriter.rewrite("what time is the game this evening"),
        )
    }

    @Test
    fun this_morning_and_afternoon() {
        assertEquals(
            "did the markets open up 2026-05-10 morning",
            rewriter.rewrite("did the markets open up this morning"),
        )
        assertEquals(
            "what happened in tech 2026-05-10 afternoon",
            rewriter.rewrite("what happened in tech this afternoon"),
        )
    }

    @Test
    fun last_week_and_this_week_resolve_to_week_of_iso() {
        assertEquals(
            "biggest tech news week of 2026-05-03",
            rewriter.rewrite("biggest tech news last week"),
        )
        assertEquals(
            "biggest tech news week of 2026-05-10",
            rewriter.rewrite("biggest tech news this week"),
        )
    }

    @Test
    fun last_month_resolves_to_month_year() {
        assertEquals(
            "best phone reviews April 2026",
            rewriter.rewrite("best phone reviews last month"),
        )
    }

    @Test
    fun this_year_and_last_year_resolve_to_year() {
        assertEquals(
            "stock market summary 2025",
            rewriter.rewrite("stock market summary last year"),
        )
        assertEquals(
            "stock market summary 2026",
            rewriter.rewrite("stock market summary this year"),
        )
    }

    // -- Future relative expressions (PR #43) ------------------------------

    @Test
    fun tomorrow_resolves_to_iso() {
        assertEquals(
            "any games 2026-05-11",
            rewriter.rewrite("any games tomorrow"),
        )
    }

    @Test
    fun tomorrow_night_resolves_before_tomorrow() {
        // "tomorrow night" must match before the bare "tomorrow" rule.
        assertEquals(
            "what's on tv 2026-05-11 evening",
            rewriter.rewrite("what's on tv tomorrow night"),
        )
    }

    @Test
    fun next_week_resolves_to_week_of_iso() {
        assertEquals(
            "fixtures week of 2026-05-17",
            rewriter.rewrite("fixtures next week"),
        )
    }

    @Test
    fun next_month_resolves_to_month_year() {
        // The reported case: May 2026 + 1 month → June 2026.
        assertEquals(
            "what are the big sporting events June 2026",
            rewriter.rewrite("what are the big sporting events next month"),
        )
    }

    @Test
    fun next_year_resolves_to_year() {
        assertEquals(
            "world cup schedule 2027",
            rewriter.rewrite("world cup schedule next year"),
        )
    }

    @Test
    fun rules_are_case_insensitive() {
        assertEquals(
            "what happened 2026-05-09",
            rewriter.rewrite("what happened YESTERDAY"),
        )
    }

    @Test
    fun multiple_substitutions_in_one_query() {
        assertEquals(
            "compare 2026 sales to 2025 sales",
            rewriter.rewrite("compare this year sales to last year sales"),
        )
    }

    @Test
    fun word_boundaries_prevent_substring_match() {
        // "today" → "yesterday" rule shouldn't match "todays" or compound words.
        // The actual rule only catches whole-word "yesterday" so this is a sanity check.
        assertEquals("yesterdays papers", rewriter.rewrite("yesterdays papers"))
    }

    // -- Memory-reference abort --------------------------------------------

    @Test
    fun my_team_aborts() {
        assertNull(rewriter.rewrite("did my team win"))
    }

    @Test
    fun where_i_live_aborts() {
        assertNull(rewriter.rewrite("what's the weather where i live"))
    }

    @Test
    fun my_company_aborts() {
        assertNull(rewriter.rewrite("did my company stock go up"))
    }

    @Test
    fun i_live_in_aborts() {
        assertNull(rewriter.rewrite("traffic in the city i live in"))
    }

    @Test
    fun no_possessive_passes_through() {
        assertEquals(
            "did the eagles win 2026-05-09",
            rewriter.rewrite("did the eagles win yesterday"),
        )
    }

    @Test
    fun mommy_does_not_match_my_X_pattern() {
        // The MEMORY_REFERENCE_REGEX uses \\b so "mommy" should not trigger
        // (it's a single word, no space-separated possessive).
        assertEquals("how old is mommy long legs", rewriter.rewrite("how old is mommy long legs"))
    }

    // -- Empty / short outputs ---------------------------------------------

    @Test
    fun empty_input_returns_null() {
        assertNull(rewriter.rewrite(""))
        assertNull(rewriter.rewrite("   "))
    }

    @Test
    fun single_word_input_aborts() {
        assertNull(rewriter.rewrite("today"))
    }
}
