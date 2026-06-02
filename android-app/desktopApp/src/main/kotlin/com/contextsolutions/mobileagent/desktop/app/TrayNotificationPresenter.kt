package com.contextsolutions.mobileagent.desktop.app

import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState
import com.contextsolutions.mobileagent.notification.AppNotification
import com.contextsolutions.mobileagent.notification.NotificationKind
import com.contextsolutions.mobileagent.notification.NotificationPresenter

/**
 * [NotificationPresenter] backed by Compose Desktop's [TrayState]
 * (docs/DESKTOP_PORT_PLAN.md, Phase 7). Installed as the delegate of the
 * shared `MutableNotificationPresenter` once the tray composes, so clock fires
 * (timer/alarm) and task-queue completions surface as system-tray toasts.
 *
 * Tray notifications are transient toasts with no OS-level "ongoing" / dismiss
 * affordance, so [dismiss] is a no-op and an alarm's `ongoing` flag degrades to
 * a one-shot toast on desktop (the richer ringing UX is out of scope here).
 */
class TrayNotificationPresenter(
    private val trayState: TrayState,
) : NotificationPresenter {

    override fun present(notification: AppNotification) {
        trayState.sendNotification(
            Notification(
                title = notification.title,
                message = notification.body,
                type = when (notification.kind) {
                    NotificationKind.ALARM, NotificationKind.TIMER -> Notification.Type.Warning
                    NotificationKind.TASK, NotificationKind.INFO -> Notification.Type.Info
                },
            ),
        )
    }

    override fun dismiss(id: String) {
        // Tray toasts are transient; nothing to actively clear.
    }
}
