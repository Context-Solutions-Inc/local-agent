package com.contextsolutions.localagent.notification

/**
 * Default desktop [NotificationPresenter] (docs/DESKTOP_PORT_PLAN.md, Phase 7).
 *
 * Logs the notification rather than rendering it — a placeholder bound in
 * `:shared`'s `desktopModule` so the headless graph (harness, DI_CHECK, the
 * clock subsystem) resolves and fires end-to-end without a UI. The system-tray
 * increment provides a `TrayState.sendNotification`-backed presenter in
 * `:desktopApp` that overrides this binding once a window/tray exists. Keeping a
 * logging default here means the clock tool's timer/alarm fires are observable
 * in a headless run and never NPE on a missing presenter.
 */
class DesktopNotificationPresenter(
    private val logger: (String) -> Unit = { System.err.println("[Notification] $it") },
) : NotificationPresenter {

    override fun present(notification: AppNotification) {
        logger(
            "${notification.kind} ${notification.id}: ${notification.title} — ${notification.body}" +
                if (notification.ongoing) " (ongoing)" else "",
        )
    }

    override fun dismiss(id: String) {
        logger("dismiss $id")
    }
}
