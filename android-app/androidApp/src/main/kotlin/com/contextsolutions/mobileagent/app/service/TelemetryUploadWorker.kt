package com.contextsolutions.mobileagent.app.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.contextsolutions.mobileagent.telemetry.TelemetryUploader
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * M6 Phase C — periodic telemetry upload.
 *
 * Cadence: 24h periodic interval, single shot per cycle. WorkManager
 * coalesces with battery + idle constraints to defer the actual fire when
 * convenient (PRD §4.3 — no aggressive background work; PRD §4.4 — UNMETERED
 * only).
 *
 * Why a CoroutineWorker (not @HiltWorker): same reasoning as
 * [ModelDownloadWorker] — the worker pulls [TelemetryUploader] via
 * [EntryPointAccessors] so the hilt-work artifact stays out of the
 * dependency graph. The uploader does the consent check itself, so this
 * worker is a thin wrapper.
 *
 * Cancellation: failures (network blip, partial flush) return `Result.retry`
 * so WorkManager re-enqueues with the request's backoff policy. The
 * idempotency model of [TelemetryUploader.upload] (mark-uploaded inside a
 * SQL transaction, drop-on-uncertain) makes retry safe.
 */
class TelemetryUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val uploader: TelemetryUploader by lazy {
        EntryPointAccessors
            .fromApplication(applicationContext, TelemetryUploadEntryPoint::class.java)
            .telemetryUploader()
    }

    override suspend fun doWork(): Result = try {
        val includeCurrent = inputData.getBoolean(KEY_INCLUDE_CURRENT_WINDOW, false)
        val outcome = uploader.upload(includeCurrentWindow = includeCurrent)
        Log.i(
            TAG,
            "telemetry upload outcome=${outcome::class.simpleName}" +
                if (includeCurrent) " (include_current_window=true)" else "",
        )
        Result.success()
    } catch (t: Throwable) {
        // Failed telemetry must NEVER surface to the user. Log + retry.
        Log.w(TAG, "telemetry upload failed: ${t.message}", t)
        Result.retry()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TelemetryUploadEntryPoint {
        fun telemetryUploader(): TelemetryUploader
    }

    companion object {
        private const val TAG = "TelemetryWorker"
        const val UNIQUE_WORK_NAME = "telemetry-upload-periodic"
        const val UNIQUE_WORK_NAME_ONE_SHOT = "telemetry-upload-debug-oneshot"

        /**
         * InputData key that flips the uploader into "include today's
         * still-open UTC window" mode. The periodic worker (production
         * schedule) does NOT set this — its counters land tomorrow.
         * The debug button sets it true so a developer can verify the
         * pipeline immediately. See [TelemetryUploader.upload] for the
         * trade-off (today's partial counts get marked uploaded; any
         * further increments in the same window are dropped on the next
         * upload).
         */
        const val KEY_INCLUDE_CURRENT_WINDOW = "include_current_window"

        /**
         * Debug-only one-shot enqueue. Bypasses the 24h periodic schedule
         * AND the "closed window only" filter — see [KEY_INCLUDE_CURRENT_WINDOW].
         * Gated behind `BuildConfig.DEBUG` at the call site (Settings).
         * The uploader still gates on consent so this never accidentally
         * sends data when the toggle is OFF.
         *
         * `ExistingWorkPolicy.REPLACE` means a rapid double-tap on the
         * button schedules only one work execution — the second tap
         * cancels the first if it hasn't run yet.
         */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<TelemetryUploadWorker>()
                .setInputData(workDataOf(KEY_INCLUDE_CURRENT_WINDOW to true))
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    UNIQUE_WORK_NAME_ONE_SHOT,
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
        }

        /**
         * Enqueue the periodic worker idempotently. Called from
         * [com.contextsolutions.mobileagent.app.MobileAgentApplication.onCreate] —
         * KEEP policy means a re-launch after a worker is already scheduled
         * is a no-op (no double-runs, no schedule churn).
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TelemetryUploadWorker>(
                repeatInterval = 24,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
                // Flex window: WorkManager may fire anywhere in the last
                // PERIODIC_FLEX_HOURS of the 24h window. Reduces battery
                // impact by letting the system batch with other periodic
                // work.
                flexTimeInterval = 4,
                flexTimeIntervalUnit = TimeUnit.HOURS,
            )
                .setConstraints(
                    Constraints.Builder()
                        // PRD §4.4 — telemetry never travels on metered
                        // connections (data plan cost is the user's, not
                        // ours).
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        // No charging requirement — 24h periodic on UNMETERED
                        // is already low-pressure; gating on charging would
                        // strand uploads for users who rarely plug in.
                        .build(),
                )
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
        }
    }
}
