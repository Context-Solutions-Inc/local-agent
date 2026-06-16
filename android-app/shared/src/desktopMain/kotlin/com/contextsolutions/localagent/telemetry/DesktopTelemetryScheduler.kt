package com.contextsolutions.localagent.telemetry

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Desktop replacement for Android's `TelemetryUploadWorker` (WorkManager) —
 * docs/DESKTOP_PORT_PLAN.md, Phase 7. A single long-lived coroutine that calls
 * [TelemetryUploader.upload] on a fixed cadence (default 24 h, matching the
 * Android worker's period). The desktop has no WorkManager / metered-network
 * constraint API, so cadence is the only knob; the consent gate lives inside
 * `upload()` (it returns `SkippedConsent` when the user has opted out), so this
 * loop is safe to run unconditionally.
 *
 * Not started by DI — the app/tray increment calls [start] once the warm-model
 * app scope exists (the same lifecycle that calls `ClockService.rearmAll()`),
 * and [stop]s it on quit. Errors per pass are swallowed so a transient failure
 * doesn't kill the loop.
 */
class DesktopTelemetryScheduler(
    private val uploader: TelemetryUploader,
    private val periodMs: Long = DEFAULT_PERIOD_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val logger: (String) -> Unit = {},
) {
    private var job: Job? = null

    /** Begin the periodic upload loop. Idempotent — a second call is a no-op while running. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                delay(periodMs)
                try {
                    val outcome = uploader.upload()
                    logger("upload outcome: $outcome")
                } catch (t: Throwable) {
                    logger("upload failed: ${t.message}")
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val DEFAULT_PERIOD_MS = 24L * 60L * 60L * 1_000L // 24h, matches the Android worker
    }
}
