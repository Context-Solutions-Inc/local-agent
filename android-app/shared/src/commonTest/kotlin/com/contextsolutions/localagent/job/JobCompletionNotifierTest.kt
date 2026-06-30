package com.contextsolutions.localagent.job

import com.contextsolutions.localagent.i18n.englishStringCatalog
import com.contextsolutions.localagent.notification.AppNotification
import com.contextsolutions.localagent.notification.NotificationKind
import com.contextsolutions.localagent.notification.NotificationPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class JobCompletionNotifierTest {

    private class RecordingPresenter : NotificationPresenter {
        val shown = mutableListOf<AppNotification>()
        override fun present(notification: AppNotification) { shown.add(notification) }
        override fun dismiss(id: String) = Unit
    }

    @Test
    fun baselineEmissionSuppressesAlreadyFinishedRuns() = runTest {
        val repo = FakeJobRepository(listOf(jobWithRun("a", JobRunStatus.SUCCEEDED, 100)))
        val prefs = FakeJobNotificationPrefs()
        val presenter = RecordingPresenter()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        JobCompletionNotifier(repo, presenter, prefs, englishStringCatalog()).start(scope)

        assertTrue(presenter.shown.isEmpty(), "initial backfill must not notify")
        assertEquals(100, prefs.notifiedWatermark(), "baseline advances the watermark")
        scope.cancel()
    }

    @Test
    fun notifiesNewSucceededAndFailedButNotRunningOrCancelled() = runTest {
        val repo = FakeJobRepository(emptyList())
        val prefs = FakeJobNotificationPrefs()
        val presenter = RecordingPresenter()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        JobCompletionNotifier(repo, presenter, prefs, englishStringCatalog()).start(scope) // baseline (empty)

        repo.emit(
            listOf(
                jobWithRun("a", JobRunStatus.SUCCEEDED, 100, name = "Backup"),
                jobWithRun("b", JobRunStatus.FAILED, 200, name = "Sync"),
                jobWithRun("c", JobRunStatus.RUNNING, 300),
                jobWithRun("d", JobRunStatus.CANCELLED, 400),
            ),
        )

        assertEquals(2, presenter.shown.size)
        val a = presenter.shown.first { it.id == "job_run:a" }
        val b = presenter.shown.first { it.id == "job_run:b" }
        assertEquals(NotificationKind.JOB, a.kind)
        assertEquals("Job finished", a.title)
        assertEquals("Job failed", b.title)
        assertTrue(a.body.contains("Backup"))
        assertEquals(200, prefs.notifiedWatermark(), "watermark = max noteworthy run")
        scope.cancel()
    }

    @Test
    fun reEmittingSameRowsDoesNotReNotify() = runTest {
        val repo = FakeJobRepository(emptyList())
        val prefs = FakeJobNotificationPrefs()
        val presenter = RecordingPresenter()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        JobCompletionNotifier(repo, presenter, prefs, englishStringCatalog()).start(scope)

        val rows = listOf(jobWithRun("a", JobRunStatus.SUCCEEDED, 100))
        repo.emit(rows)
        repo.emit(rows) // reconcile re-emits identical state

        assertEquals(1, presenter.shown.size)
        scope.cancel()
    }

    @Test
    fun persistedWatermarkBlocksReNotifyAfterRestart() = runTest {
        // Simulate a fresh process: prefs already say we notified up to t=200.
        val repo = FakeJobRepository(
            listOf(
                jobWithRun("a", JobRunStatus.SUCCEEDED, 150),
                jobWithRun("b", JobRunStatus.FAILED, 200),
            ),
        )
        val prefs = FakeJobNotificationPrefs(seedNotified = 200)
        val presenter = RecordingPresenter()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        JobCompletionNotifier(repo, presenter, prefs, englishStringCatalog()).start(scope) // baseline — already-seen rows suppressed

        repo.emit(
            listOf(
                jobWithRun("a", JobRunStatus.SUCCEEDED, 150),
                jobWithRun("b", JobRunStatus.FAILED, 200),
                jobWithRun("c", JobRunStatus.SUCCEEDED, 250), // genuinely new
            ),
        )

        assertEquals(1, presenter.shown.size)
        assertEquals("job_run:c", presenter.shown.single().id)
        scope.cancel()
    }
}
