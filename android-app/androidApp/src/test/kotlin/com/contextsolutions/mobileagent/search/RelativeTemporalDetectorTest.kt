package com.contextsolutions.mobileagent.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelativeTemporalDetectorTest {

    private val detector = RelativeTemporalDetector()

    private fun assertMatches(vararg queries: String) {
        for (q in queries) assertTrue("expected match: \"$q\"", detector.matches(q))
    }

    private fun assertNoMatch(vararg queries: String) {
        for (q in queries) assertFalse("expected NO match: \"$q\"", detector.matches(q))
    }

    @Test
    fun past_references_match() = assertMatches(
        "did the eagles win last year",
        "who won the super bowl last year", // the reported bug
        "who won yesterday",
        "what happened last night",
        "scores from last week",
        "news from 3 days ago",
        "an hour ago",
        "a week ago",
        "what did I miss the other day",
        "anything interesting recently",
        "what's been going on lately",
        "last month's results",
    )

    @Test
    fun present_references_match() = assertMatches(
        "what's the score today",
        "any games tonight",
        "this morning's headlines",
        "what's happening right now",
        "what's currently trending",
        "news at the moment",
        "what's popular these days",
        "this week's schedule",
        "anything this weekend",
        "this year's standings",
    )

    @Test
    fun future_references_match() = assertMatches(
        "any games tomorrow",
        "next week's schedule",
        "upcoming fixtures",
        "what's on later today",
        "next month's earnings",
    )

    @Test
    fun case_insensitive() = assertMatches("Who Won The Super Bowl LAST YEAR")

    @Test
    fun absolute_references_do_not_match() = assertNoMatch(
        "who won the super bowl in 2019",
        "on jan 5 2020",
        "events in 2024",
        "the 2018 world cup",
    )

    @Test
    fun non_temporal_queries_do_not_match() = assertNoMatch(
        "what is photosynthesis",
        "capital of france",
        "i know how to cook", // no bare-"now" arm
        "now that i think about it", // no bare-"now" arm
        "how do tides work",
    )
}
