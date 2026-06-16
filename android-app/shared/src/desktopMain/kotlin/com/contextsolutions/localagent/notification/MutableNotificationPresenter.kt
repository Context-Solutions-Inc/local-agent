package com.contextsolutions.localagent.notification

/**
 * A [NotificationPresenter] whose backing delegate can be swapped at runtime
 * (docs/DESKTOP_PORT_PLAN.md, Phase 7). Bound as the single desktop
 * `NotificationPresenter`, so the clock subsystem and the task queue both
 * resolve THIS one instance.
 *
 * The chicken-and-egg it solves: the real tray-backed presenter needs Compose
 * Desktop's `TrayState`, which only exists once the `application { }`
 * composition starts — well after the Koin graph (and any early alarm fire) is
 * built. So this starts delegating to [fallback] (a logging
 * [DesktopNotificationPresenter]); once the tray composes, the app calls
 * [setDelegate] with a `TrayState`-backed presenter and every subsequent
 * notification (clock + task) routes to the system tray.
 */
class MutableNotificationPresenter(
    private val fallback: NotificationPresenter = DesktopNotificationPresenter(),
) : NotificationPresenter {

    @Volatile
    private var delegate: NotificationPresenter? = null

    /** Install the real presenter (called once the tray exists). */
    fun setDelegate(presenter: NotificationPresenter) {
        delegate = presenter
    }

    override fun present(notification: AppNotification) {
        (delegate ?: fallback).present(notification)
    }

    override fun dismiss(id: String) {
        (delegate ?: fallback).dismiss(id)
    }
}
