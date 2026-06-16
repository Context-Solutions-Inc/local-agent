package com.contextsolutions.localagent.notification

/**
 * Cross-platform notification seam (docs/DESKTOP_PORT_PLAN.md, Phase 7).
 *
 * Introduced for the desktop port: the desktop clock subsystem
 * ([com.contextsolutions.localagent.clock.AlarmScheduler]) and the queued
 * agent-task system both need to surface a system notification, but neither can
 * reference Android's `NotificationManager` / Compose Desktop's `TrayState`
 * directly. This interface is the common contract; the desktop impl maps it onto
 * `TrayState.sendNotification` (a later Phase-7 increment provides the
 * tray-backed presenter in `:desktopApp`; until then a logging presenter stands
 * in so the headless graph resolves). Android continues to use its existing
 * `ClockNotifications` for the clock path and does not bind this seam yet — it
 * can adopt it during the Phase-9 UI cutover.
 */
interface NotificationPresenter {

    /** Show (or refresh) a notification. Re-presenting the same [AppNotification.id] updates it. */
    fun present(notification: AppNotification)

    /**
     * Clear a previously-presented notification by id. Used to stop a ringing
     * alarm's ongoing notification (the desktop counterpart of dismissing the
     * Android firing-service notification). No-op if nothing is showing for [id].
     */
    fun dismiss(id: String)
}

/** What kind of event a notification represents — lets a presenter pick an icon/sound/urgency. */
enum class NotificationKind { ALARM, TIMER, TASK, JOB, INFO }

/**
 * A platform-agnostic notification payload. [id] namespaces the notification so
 * it can be updated or dismissed; [ongoing] marks a notification that should
 * persist until explicitly [NotificationPresenter.dismiss]ed (a ringing alarm)
 * rather than auto-dismissing (a one-shot timer chime / task-complete toast).
 */
data class AppNotification(
    val id: String,
    val title: String,
    val body: String,
    val kind: NotificationKind = NotificationKind.INFO,
    val ongoing: Boolean = false,
)
