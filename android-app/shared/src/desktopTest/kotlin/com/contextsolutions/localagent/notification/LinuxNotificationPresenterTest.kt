package com.contextsolutions.localagent.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * notify-send argv assembly for the Linux desktop presenter (PR #93). Pure string
 * assembly — no process is spawned.
 */
class LinuxNotificationPresenterTest {

    private val presenter = LinuxNotificationPresenter(logger = {})

    private fun notification(
        kind: NotificationKind,
        ongoing: Boolean = false,
        title: String = "Title",
        body: String = "Body",
    ) = AppNotification(id = "id", title = title, body = body, kind = kind, ongoing = ongoing)

    @Test
    fun `argv carries the notify-send command, app name, title and body`() {
        val argv = presenter.buildArgv(notification(NotificationKind.JOB, title = "Job finished", body = "nightly"))
        assertEquals("notify-send", argv.first())
        assertTrue(argv.contains("--app-name=Local Agent"), argv.toString())
        // Title then body are the trailing positional args.
        assertEquals(listOf("Job finished", "nightly"), argv.takeLast(2))
    }

    @Test
    fun `alarm and timer map to critical urgency`() {
        assertTrue(presenter.buildArgv(notification(NotificationKind.ALARM)).contains("--urgency=critical"))
        assertTrue(presenter.buildArgv(notification(NotificationKind.TIMER)).contains("--urgency=critical"))
    }

    @Test
    fun `task, job and info map to normal urgency`() {
        for (kind in listOf(NotificationKind.TASK, NotificationKind.JOB, NotificationKind.INFO)) {
            assertTrue(
                presenter.buildArgv(notification(kind)).contains("--urgency=normal"),
                "expected normal urgency for $kind",
            )
        }
    }

    @Test
    fun `ongoing is critical regardless of kind`() {
        assertTrue(
            presenter.buildArgv(notification(NotificationKind.INFO, ongoing = true)).contains("--urgency=critical"),
        )
    }
}
