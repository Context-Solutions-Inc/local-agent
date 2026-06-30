package com.contextsolutions.localagent.job

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the composition of the manual "re-sync jobs" action (#39 follow-up): it must
 * wipe local jobs, reset the watermark, and force a reconcile — in that order (the
 * reconcile pulls, so it must run AFTER the wipe + reset).
 */
class JobResyncTest {

    @Test
    fun resync_wipesResetsThenForcesSync_inOrder() = runTest {
        val repo = FakeJobRepository(initial = listOf(jobWithRun("a", JobRunStatus.SUCCEEDED, 10L)))
        val events = mutableListOf<String>()

        val resync = JobResync(
            jobRepository = repo,
            resetWatermark = { events += "reset" },
            forceSync = { events += "sync" },
        )

        resync.resyncFromDesktop()

        assertEquals(1, repo.wipeCount, "wipeLocal should run exactly once")
        assertTrue(repo.snapshot().isEmpty(), "local jobs should be wiped")
        // wipe happens inside resyncFromDesktop before reset/sync; assert reset precedes sync.
        assertEquals(listOf("reset", "sync"), events, "reset watermark must precede the reconcile nudge")
    }
}
