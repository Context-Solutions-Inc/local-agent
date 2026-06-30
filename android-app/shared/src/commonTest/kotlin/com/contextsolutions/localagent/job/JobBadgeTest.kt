package com.contextsolutions.localagent.job

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class JobBadgeTest {

    @Test
    fun countsNoteworthyRunsNewerThanWatermark() = runTest {
        val repo = FakeJobRepository(
            listOf(
                jobWithRun("a", JobRunStatus.SUCCEEDED, 100),
                jobWithRun("b", JobRunStatus.FAILED, 200),
            ),
        )
        val prefs = FakeJobNotificationPrefs(seenInit = 0)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val badge = JobBadge(repo, prefs, scope)
        scope.launch { badge.unseenCount.collect {} } // activate WhileSubscribed

        assertEquals(2, badge.unseenCount.value)
        scope.cancel()
    }

    @Test
    fun runningAndCancelledRunsAreNotCounted() = runTest {
        val repo = FakeJobRepository(
            listOf(
                jobWithRun("a", JobRunStatus.RUNNING, 100),
                jobWithRun("b", JobRunStatus.CANCELLED, 200),
                jobWithRun("c", null, null),
            ),
        )
        val prefs = FakeJobNotificationPrefs(seenInit = 0)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val badge = JobBadge(repo, prefs, scope)
        scope.launch { badge.unseenCount.collect {} }

        assertEquals(0, badge.unseenCount.value)
        scope.cancel()
    }

    @Test
    fun markSeenClearsTheCount() = runTest {
        val repo = FakeJobRepository(listOf(jobWithRun("a", JobRunStatus.FAILED, 100)))
        val prefs = FakeJobNotificationPrefs(seenInit = 0)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val badge = JobBadge(repo, prefs, scope)
        scope.launch { badge.unseenCount.collect {} }
        assertEquals(1, badge.unseenCount.value)

        badge.markSeen()

        assertEquals(0, badge.unseenCount.value)
        scope.cancel()
    }
}
