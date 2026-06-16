package com.contextsolutions.localagent.job

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PR #88 — anchored "run job …" command detection + prefix stripping. */
class RunJobDetectorTest {

    private val detector = RunJobDetector()

    @Test
    fun matches_leading_run_job_case_insensitive() {
        assertTrue(detector.matches("run job property search Westport, Ontario"))
        assertTrue(detector.matches("Run Job Property Search foo"))
        assertTrue(detector.matches("  RUN   JOB  daily report "))
        assertTrue(detector.matches("run job: report")) // colon separator
    }

    @Test
    fun does_not_match_when_command_is_not_leading() {
        assertFalse(detector.matches("how do I run jobs in cron"))
        assertFalse(detector.matches("can you run job schedulers locally"))
        assertFalse(detector.matches("tell me about the run job feature"))
    }

    @Test
    fun does_not_match_plural_jobs() {
        // `job\b` requires a word boundary that "jobs" doesn't provide.
        assertFalse(detector.matches("run jobs"))
        assertFalse(detector.matches("run jobs now"))
    }

    @Test
    fun strip_prefix_preserves_remainder_casing() {
        assertEquals(
            "property search Westport, Ontario",
            detector.stripPrefix("run job property search Westport, Ontario"),
        )
        assertEquals("Daily Report", detector.stripPrefix("Run Job Daily Report"))
        assertEquals("report", detector.stripPrefix("run job: report"))
    }

    @Test
    fun strip_prefix_empty_when_nothing_follows() {
        assertEquals("", detector.stripPrefix("run job"))
        assertEquals("", detector.stripPrefix("run job   "))
    }
}
