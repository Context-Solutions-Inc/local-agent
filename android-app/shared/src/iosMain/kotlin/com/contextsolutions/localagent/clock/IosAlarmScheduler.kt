package com.contextsolutions.localagent.clock

import com.contextsolutions.localagent.platform.AgentClock
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject

/**
 * iOS [AlarmScheduler] (PR #41) backed by `UNUserNotificationCenter` local
 * notifications. Unlike Android's `AlarmManager` or the desktop coroutine-`delay`
 * scheduler (which only fires while the process is alive), a scheduled local
 * notification is delivered by iOS itself, so a timer/alarm fires even when the
 * app is backgrounded or the device is locked.
 *
 * Notes:
 *  - **One-shot trigger.** Each arm schedules a single `UNTimeIntervalNotificationTrigger`
 *    keyed by `"timer:<id>"` / `"alarm:<id>"`. Re-arming the same id first removes the
 *    prior pending request (the interface's idempotency contract). Past-due instants
 *    are skipped (iOS rejects a non-positive interval).
 *  - **Recurring alarms are best-effort:** the OS delivers the notification but does
 *    NOT call back into [ClockService.onAlarmFired], so the next occurrence is re-armed
 *    only when the app next runs [ClockService.rearmAll]. Timers are one-shot, so they
 *    are fully correct. (A `BGTaskScheduler` re-arm is a follow-up.)
 *  - **Foreground presentation.** iOS suppresses notifications while the app is in the
 *    foreground unless a delegate opts in; [foregroundDelegate] returns banner+sound so
 *    a timer that fires while the user is in the app still shows + chimes.
 *  - **Authorization** is requested once on construction (alert+sound+badge). If the
 *    user denies it, requests are still scheduled but the OS won't display them.
 */
class IosAlarmScheduler(
    private val clockServiceProvider: () -> ClockService,
    private val clock: AgentClock = AgentClock(),
    private val logger: (String) -> Unit = {},
) : AlarmScheduler {

    private val center = UNUserNotificationCenter.currentNotificationCenter()
    private val foregroundDelegate = ForegroundPresentationDelegate()

    init {
        center.setDelegate(foregroundDelegate)
        center.requestAuthorizationWithOptions(
            UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
        ) { granted, error ->
            if (error != null) {
                logger("notification authorization error: ${error.localizedDescription}")
            } else {
                logger("notification authorization granted=$granted")
            }
        }
    }

    override fun scheduleTimer(timerId: String, fireAtEpochMs: Long) {
        val label = runCatching {
            clockServiceProvider().timersSnapshot().firstOrNull { it.id == timerId }?.label
        }.getOrNull()
        schedule(notificationId(TIMER_PREFIX, timerId), fireAtEpochMs, "Timer finished", label ?: "Your timer is up.")
    }

    override fun cancelTimer(timerId: String) = cancel(notificationId(TIMER_PREFIX, timerId))

    override fun scheduleAlarm(alarmId: String, fireAtEpochMs: Long) {
        val label = runCatching {
            clockServiceProvider().alarmsSnapshot().firstOrNull { it.id == alarmId }?.label
        }.getOrNull()
        schedule(notificationId(ALARM_PREFIX, alarmId), fireAtEpochMs, "Alarm", label ?: "Alarm ringing.")
    }

    override fun cancelAlarm(alarmId: String) = cancel(notificationId(ALARM_PREFIX, alarmId))

    // iOS local notifications can't ring continuously; "stop" == clear the delivered one.
    override fun stopFiringAlarm(alarmId: String) = cancel(notificationId(ALARM_PREFIX, alarmId))

    private fun schedule(id: String, fireAtEpochMs: Long, title: String, body: String) {
        val seconds = (fireAtEpochMs - clock.nowEpochMs()) / 1000.0
        if (seconds <= 0.0) {
            logger("skip past-due notification $id")
            return
        }
        center.removePendingNotificationRequestsWithIdentifiers(listOf(id))
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(body)
            setSound(UNNotificationSound.defaultSound)
        }
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(seconds, repeats = false)
        val request = UNNotificationRequest.requestWithIdentifier(id, content, trigger)
        center.addNotificationRequest(request, withCompletionHandler = null)
        logger("armed $id in ${seconds}s")
    }

    private fun cancel(id: String) {
        center.removePendingNotificationRequestsWithIdentifiers(listOf(id))
        center.removeDeliveredNotificationsWithIdentifiers(listOf(id))
    }

    private fun notificationId(prefix: String, id: String): String = "$prefix$id"

    private companion object {
        const val TIMER_PREFIX = "timer:"
        const val ALARM_PREFIX = "alarm:"
    }
}

/**
 * Presents notifications even while the app is foregrounded (iOS otherwise drops them).
 * Held strongly by [IosAlarmScheduler] since `UNUserNotificationCenter.delegate` is weak.
 */
private class ForegroundPresentationDelegate : NSObject(), UNUserNotificationCenterDelegateProtocol {
    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        willPresentNotification: UNNotification,
        withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
    ) {
        withCompletionHandler(UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionSound)
    }
}
