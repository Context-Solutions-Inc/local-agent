package com.contextsolutions.mobileagent.notification

/**
 * Linux desktop [NotificationPresenter] that delivers notifications via the
 * freedesktop `notify-send` CLI (libnotify), instead of the AWT system-tray
 * toasts used on macOS/Windows (PR #93).
 *
 * Why: on modern GNOME/Wayland the AWT `SystemTray` is usually unsupported, so
 * tray toasts silently degrade to the stderr-logging [DesktopNotificationPresenter]
 * and the user sees nothing; even where a tray exists the toasts look poor. Shelling
 * out to `notify-send` is the native, good-looking path on Linux — the same
 * fire-and-forget `ProcessBuilder` pattern used by the desktop TTS (`spd-say`).
 *
 * Best-effort by design (matches the tray presenter): missing `notify-send` or no
 * D-Bus session (true headless server) → the spawn throws, we catch + log, never
 * crash. [dismiss] is a no-op — like the tray's transient toasts an `ongoing`
 * notification is instead mapped to `--urgency=critical` (no auto-expire) and the
 * user clears it.
 */
class LinuxNotificationPresenter(
    private val logger: (String) -> Unit = { System.err.println("[Notification] $it") },
) : NotificationPresenter {

    override fun present(notification: AppNotification) {
        runCatching {
            ProcessBuilder(buildArgv(notification)).start()
        }.onFailure {
            // No notify-send / no D-Bus session — degrade to a log, never crash.
            logger("notify-send failed for ${notification.id}: ${it.message}")
        }
    }

    override fun dismiss(id: String) {
        // notify-send notifications are transient (or critical-persistent); we don't
        // track server-assigned ids, so there's nothing to actively close. Matches
        // the AWT tray presenter's no-op dismiss.
        logger("dismiss $id (no-op)")
    }

    internal fun buildArgv(notification: AppNotification): List<String> = buildList {
        add("notify-send")
        add("--app-name=Mobile Agent")
        add("--urgency=${urgencyFor(notification)}")
        add(notification.title)
        add(notification.body)
    }

    /**
     * libnotify urgency. ALARM/TIMER (and anything [AppNotification.ongoing]) map to
     * `critical`, which most servers keep on screen until dismissed — the closest
     * equivalent to an ongoing notification without tracking close ids. Exhaustive
     * over [NotificationKind] (keep it that way — see invariant #58).
     */
    private fun urgencyFor(notification: AppNotification): String {
        if (notification.ongoing) return "critical"
        return when (notification.kind) {
            NotificationKind.ALARM, NotificationKind.TIMER -> "critical"
            NotificationKind.TASK, NotificationKind.JOB, NotificationKind.INFO -> "normal"
        }
    }
}
