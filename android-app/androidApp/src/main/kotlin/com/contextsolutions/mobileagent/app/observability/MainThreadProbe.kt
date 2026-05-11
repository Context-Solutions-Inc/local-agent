package com.contextsolutions.mobileagent.app.observability

import android.os.Handler
import android.os.Looper
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicLong

/**
 * Two-call abstraction the [MainThreadHeartbeatWatchdog] uses to detect
 * main-thread stalls. Separated from the watchdog so tests can drive the
 * "stalled vs ack'd" signal synchronously without a real [Looper].
 *
 *  - [pingMainThread] queues a no-op on the main thread's message queue.
 *  - [ageOfLastAckMs] returns how long ago the *previous* ping completed
 *    (i.e. how stale the main thread's responsiveness is right now).
 *
 * The production impl uses [System.nanoTime] — monotonic, wall-clock-immune,
 * and available on plain JVM (so unit tests don't need Robolectric). The
 * watchdog only runs while the app is foregrounded so deep-sleep skew
 * isn't a practical concern.
 */
interface MainThreadProbe {
    fun pingMainThread()
    fun ageOfLastAckMs(): Long
}

/**
 * Real probe used in production. Posts a `Runnable` to the main `Looper` that
 * bumps an atomic timestamp; the watchdog thread reads the timestamp from its
 * own thread without ever touching the `Looper`.
 */
@Singleton
class HandlerMainThreadProbe @Inject constructor() : MainThreadProbe {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastAckMs = AtomicLong(nowMs())
    private val ackRunnable = Runnable { lastAckMs.set(nowMs()) }

    override fun pingMainThread() {
        mainHandler.post(ackRunnable)
    }

    override fun ageOfLastAckMs(): Long = nowMs() - lastAckMs.get()

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L
}
