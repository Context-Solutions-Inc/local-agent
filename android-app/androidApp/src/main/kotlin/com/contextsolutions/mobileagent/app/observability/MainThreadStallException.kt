package com.contextsolutions.mobileagent.app.observability

/**
 * Thrown — well, *recorded* — by [MainThreadHeartbeatWatchdog] when the main
 * thread fails to ack the watchdog's pings for longer than the configured
 * stall threshold. Surfaced to Crashlytics as a non-fatal so dashboards
 * count stall events that would otherwise be invisible (they're not crashes
 * per se — the OS kills the device's system_server before the app itself
 * crashes).
 *
 * The message includes only the integer stall duration; never user content.
 * [com.contextsolutions.mobileagent.observability.ContentRedactor] runs as
 * defense-in-depth through [com.contextsolutions.mobileagent.observability.SafeCrashReporter].
 */
class MainThreadStallException(stallMs: Long) :
    RuntimeException("Main thread stalled ${stallMs}ms; pre-emptive Gemma unload triggered")
