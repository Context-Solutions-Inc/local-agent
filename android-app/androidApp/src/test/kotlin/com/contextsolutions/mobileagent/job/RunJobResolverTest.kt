package com.contextsolutions.mobileagent.job

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PR #88 — longest-name token-prefix matching + keyword extraction. */
class RunJobResolverTest {

    private val resolver = RunJobResolver()

    private fun job(id: String, name: String, prompt: String = "", deleted: Long? = null) = Job(
        id = id,
        name = name,
        command = "echo",
        prompt = prompt,
        workingDir = null,
        scheduleType = JobScheduleType.ONE_SHOT,
        cronExpression = null,
        fireAtEpochMs = 1,
        paused = false,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
        deletedAtEpochMs = deleted,
        lastRunStatus = null,
        lastRunAtEpochMs = null,
        lastRunSummary = null,
        lastRunConversationId = null,
    )

    @Test
    fun matches_multiword_name_and_splits_keywords() {
        val jobs = listOf(job("j1", "Property Search"))
        val r = resolver.resolve("property search Westport, Ontario", jobs)
        assertTrue(r is RunJobResolution.Match)
        r as RunJobResolution.Match
        assertEquals("j1", r.job.id)
        assertEquals("Westport, Ontario", r.keywords) // original casing + comma preserved
    }

    @Test
    fun longest_name_wins_when_two_lead() {
        val jobs = listOf(job("short", "Property Search"), job("long", "Property Search Pro"))
        val r = resolver.resolve("property search pro Toronto", jobs)
        assertTrue(r is RunJobResolution.Match)
        r as RunJobResolution.Match
        assertEquals("long", r.job.id)
        assertEquals("Toronto", r.keywords)
    }

    @Test
    fun empty_keywords_when_only_the_name_is_given() {
        val jobs = listOf(job("j1", "Daily Report"))
        val r = resolver.resolve("daily report", jobs)
        assertTrue(r is RunJobResolution.Match)
        r as RunJobResolution.Match
        assertEquals("", r.keywords) // caller falls back to job.prompt
    }

    @Test
    fun not_found_when_no_name_leads() {
        val jobs = listOf(job("j1", "Property Search"))
        val r = resolver.resolve("weather in Toronto", jobs)
        assertTrue(r is RunJobResolution.NotFound)
        r as RunJobResolution.NotFound
        assertEquals("weather in Toronto", r.requestedText)
    }

    @Test
    fun not_found_on_blank_remainder() {
        assertTrue(resolver.resolve("", listOf(job("j1", "X"))) is RunJobResolution.NotFound)
    }

    @Test
    fun skips_tombstoned_jobs() {
        val jobs = listOf(job("dead", "Property Search", deleted = 99L))
        assertTrue(resolver.resolve("property search foo", jobs) is RunJobResolution.NotFound)
    }
}
