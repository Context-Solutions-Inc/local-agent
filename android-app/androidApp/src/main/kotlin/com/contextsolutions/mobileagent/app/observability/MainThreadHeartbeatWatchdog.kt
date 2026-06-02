package com.contextsolutions.mobileagent.app.observability

import android.util.Log
import com.contextsolutions.mobileagent.app.service.AuxModelLifecycleCoordinator
import com.contextsolutions.mobileagent.app.service.InferenceSessionManager
import com.contextsolutions.mobileagent.app.service.UnloadReason
import com.contextsolutions.mobileagent.observability.SafeCrashReporter
import com.contextsolutions.mobileagent.telemetry.CounterNames
import com.contextsolutions.mobileagent.telemetry.TelemetryCounters
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground main-thread responsiveness watchdog.
 *
 * Pre-empts the OS-level deadlock that hit M7 testing on Pixel 7: Gemma
 * running on the Mali-G710 GPU saturated the kernel GPU scheduler so badly
 * that unrelated processes' main threads parked in uninterruptible kernel
 * I/O for 25+ seconds, ART's Stop-The-World GC couldn't suspend them,
 * `system_server`'s `Watchdog` (~60 s threshold) killed `system_server`,
 * and every app got `DeadSystemException` — i.e., a soft reboot.
 *
 * Our own main thread isn't necessarily the proximate cause (the GPU
 * saturation came from us; the stalls landed on whichever process touched
 * a GPU-fronted system service), but the cheapest signal we have inside
 * the app is "my own main thread is stalled". When that fires, we drop the
 * GPU context (which is the resource everyone else is fighting over)
 * before `system_server` reaches in.
 *
 * Design notes:
 *  - A dedicated daemon thread runs the watchdog loop. It cannot share a
 *    pool with anything that might be blocked by GPU saturation.
 *  - [MainThreadProbe] abstracts "post a Runnable to main + read ack age"
 *    so tests can drive the trip logic synchronously without a Looper.
 *  - [pingIntervalMs] (5 s) + ~2 consecutive misses ≈ [stallThresholdMs]
 *    (10 s) is comfortably below the OS's ~60 s threshold, well above any
 *    legitimate main-thread stall on a healthy device (Compose first-frame
 *    verification on debug builds tops out around 1–2 s per route). Tightened
 *    from 20 s so a wedged decode is caught — and the in-flight generation
 *    cancelled by [trip] → forceUnload — sooner.
 *  - First [startupGraceMs] of process lifetime suppresses trips. Cold
 *    start does class verification + DI graph construction on the main
 *    thread; the verification logs in the M7 PR#1 capture showed 200–400 ms
 *    per Compose class, and the cumulative effect can exceed the stall
 *    threshold briefly even on a healthy device.
 *  - After a trip, [remediationCooldownMs] (60 s) suppresses additional
 *    trips so we don't double-record a single incident.
 *
 * Lifecycle: bound to `ProcessLifecycleOwner` in `MobileAgentApplication` so
 * [start] runs at first foreground entry and [stop] at background exit.
 * Background stalls don't matter — the OS parks the main thread by design,
 * and Gemma on GPU only causes the system-wide failure mode while
 * SurfaceFlinger is actively compositing the app.
 */
class MainThreadHeartbeatWatchdog(
    private val sessionManager: InferenceSessionManager,
    private val auxModelCoordinator: AuxModelLifecycleCoordinator,
    private val crashReporter: SafeCrashReporter,
    private val counters: TelemetryCounters,
    private val probe: MainThreadProbe,
) {

    /** Mutable for tests; not part of the public API. */
    internal var pingIntervalMs: Long = DEFAULT_PING_INTERVAL_MS
    internal var stallThresholdMs: Long = DEFAULT_STALL_THRESHOLD_MS
    internal var startupGraceMs: Long = DEFAULT_STARTUP_GRACE_MS
    internal var remediationCooldownMs: Long = DEFAULT_REMEDIATION_COOLDOWN_MS

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var startedAtUptimeMs: Long = 0L

    @Volatile
    private var lastRemediationAtUptimeMs: Long = 0L

    /**
     * Idempotent. Safe to call from [com.contextsolutions.mobileagent.app.MobileAgentApplication.onCreate]
     * and from a `ProcessLifecycleOwner` start observer; double-call is a no-op.
     */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        startedAtUptimeMs = nowMs()
        thread = Thread(this::loop, "MainThreadWatchdog").apply {
            isDaemon = true
            // Lower-than-default priority. We never need to pre-empt anything
            // important; the thread spends 99.99% of its time sleeping. If
            // the kernel is so busy we can't get a slice, the device is
            // already in trouble.
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    /**
     * Stop the watchdog. The daemon thread exits its loop within
     * [pingIntervalMs] of this call. Safe to call before [start].
     */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        while (running.get()) {
            try {
                probe.pingMainThread()
                Thread.sleep(pingIntervalMs)
                checkForStall()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (t: Throwable) {
                // Defensive: never let a watchdog crash kill the daemon.
                // Surface the throwable so we know if this happens in the
                // wild, then loop. The watchdog must outlive any single
                // tick failure.
                Log.e(TAG, "watchdog tick threw; continuing", t)
            }
        }
    }

    /**
     * Visible for tests so they can drive the trip logic without spinning
     * up the daemon thread. Production calls this only from [loop].
     */
    internal fun checkForStall() {
        val now = nowMs()
        if (now - startedAtUptimeMs < startupGraceMs) return
        val ageMs = probe.ageOfLastAckMs()
        if (ageMs < stallThresholdMs) return
        if (now - lastRemediationAtUptimeMs < remediationCooldownMs) return
        lastRemediationAtUptimeMs = now
        trip(ageMs)
    }

    private fun trip(stallMs: Long) {
        Log.w(TAG, "main-thread stalled ${stallMs}ms — forcing model unload")
        // Counter first so the telemetry signal lands even if forceUnload
        // or recordException throws. Counters are lock-free atomics.
        counters.increment(CounterNames.MAIN_THREAD_WATCHDOG_TRIPPED_TOTAL)
        runCatching { sessionManager.forceUnload(UnloadReason.MainThreadWatchdog) }
            .onFailure { Log.e(TAG, "forceUnload threw from watchdog trip", it) }
        // PR #8 — also drop the 91 MB aux models. They sit on the same
        // GPU/CPU resources the watchdog is trying to free up.
        runCatching { auxModelCoordinator.forceUnload(UnloadReason.MainThreadWatchdog) }
            .onFailure { Log.e(TAG, "aux-model forceUnload threw from watchdog trip", it) }
        runCatching {
            crashReporter.recordException(MainThreadStallException(stallMs))
            crashReporter.flushPending()
        }.onFailure { Log.e(TAG, "crash report failed from watchdog trip", it) }
    }

    /**
     * Visible for tests — assert against the most recent trip timestamp.
     * Returns 0 if no trip has occurred since [start].
     */
    internal fun lastRemediationAtUptimeMs(): Long = lastRemediationAtUptimeMs

    /** Visible for tests — set the watchdog as "started" without spinning the daemon. */
    internal fun markStartedForTest(uptimeMs: Long) {
        running.set(true)
        startedAtUptimeMs = uptimeMs
    }

    companion object {
        private const val TAG = "MainThreadWatchdog"
        const val DEFAULT_PING_INTERVAL_MS: Long = 5_000L
        const val DEFAULT_STALL_THRESHOLD_MS: Long = 10_000L
        /** Suppress trips during cold-start class verification + DI graph construction. */
        const val DEFAULT_STARTUP_GRACE_MS: Long = 30_000L
        /** Don't re-trip during this window after a remediation — avoids double-counting one incident. */
        const val DEFAULT_REMEDIATION_COOLDOWN_MS: Long = 60_000L

        /**
         * Monotonic millis. Same source as [HandlerMainThreadProbe] so the
         * "age of last ack" delta is comparable across the two classes.
         * `System.nanoTime` is available on plain JVM, so unit tests
         * don't need Robolectric.
         */
        private fun nowMs(): Long = System.nanoTime() / 1_000_000L
    }
}
