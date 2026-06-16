package com.contextsolutions.localagent.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.contextsolutions.localagent.app.R
import org.koin.mp.KoinPlatform.getKoin

/**
 * WorkManager wrapper around [ModelDownloader].
 *
 * A plain CoroutineWorker (not a DI-instantiated worker): it grabs
 * [ModelDownloader] from the global Koin graph in a `by lazy`.
 *
 * Lifecycle:
 *  - Runs as a typed foreground worker (FOREGROUND_SERVICE_TYPE_DATA_SYNC) so
 *    Android keeps it alive across app backgrounding.
 *  - Calls [setProgressAsync] roughly every 64 KB chunk; the
 *    [ModelDownloadController] StateFlow consumes those updates.
 *  - On retryable failure, returns [Result.retry] — WorkManager applies the
 *    backoff configured on the request (exponential, 30 s base).
 *  - On permanent failure, returns [Result.failure] with the structured reason
 *    in output data so the UI can show the right error message.
 */
class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    // Phase 3: resolved from the global Koin graph (Hilt's @EntryPoint is gone). A Worker is
    // framework-instantiated, so it pulls its dependency rather than getting it injected.
    private val downloader: ModelDownloader by lazy { getKoin().get() }

    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo(0L, 0L)

    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo(0L, 0L))

        val outcome = try {
            downloader.download { written, total ->
                setProgressAsync(
                    workDataOf(
                        KEY_BYTES_DOWNLOADED to written,
                        KEY_BYTES_TOTAL to total,
                    ),
                )
                // Best-effort notification refresh — throttle to avoid hammering
                // the notification manager on every 64 KB chunk. setForegroundAsync
                // (non-suspending) is used because `onProgress` is a regular
                // (non-suspend) lambda.
                if (shouldRefreshNotification(written, total)) {
                    setForegroundAsync(createForegroundInfo(written, total))
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected exception during download", t)
            return Result.failure(errorData(DownloadError.Network(t)))
        }

        return when (outcome) {
            is DownloadOutcome.Success -> Result.success(
                workDataOf(
                    KEY_BYTES_DOWNLOADED to outcome.totalBytes,
                    KEY_BYTES_TOTAL to outcome.totalBytes,
                ),
            )
            is DownloadOutcome.Failure -> {
                Log.w(TAG, "Download failed: ${outcome.error.message}")
                if (outcome.error.isRetryable) Result.retry()
                else Result.failure(errorData(outcome.error))
            }
        }
    }

    private fun shouldRefreshNotification(written: Long, total: Long): Boolean {
        if (total <= 0L) return false
        val nowPercent = ((written * 100.0) / total).toInt()
        val last = lastReportedPercent
        if (nowPercent == last) return false
        lastReportedPercent = nowPercent
        return true
    }

    private var lastReportedPercent = -1

    private fun createForegroundInfo(written: Long, total: Long): ForegroundInfo {
        ensureChannel(applicationContext)
        val notification = buildNotification(applicationContext, written, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(context: Context, written: Long, total: Long): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.download_title))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOnlyAlertOnce(true)
            .setOngoing(true)

        if (total > 0L) {
            val percent = ((written * 100.0) / total).toInt().coerceIn(0, 100)
            builder
                .setProgress(100, percent, /* indeterminate = */ false)
                .setContentText(
                    context.getString(
                        R.string.download_text_progress,
                        percent,
                        Formatter.formatShortFileSize(context, written),
                        Formatter.formatShortFileSize(context, total),
                    ),
                )
        } else {
            builder
                .setProgress(0, 0, /* indeterminate = */ true)
                .setContentText(context.getString(R.string.download_text_starting))
        }
        return builder.build()
    }

    private fun errorData(error: DownloadError): Data = workDataOf(
        KEY_ERROR_TYPE to error.toType().name,
        KEY_ERROR_MESSAGE to error.message,
    )

    companion object {
        const val UNIQUE_WORK_NAME = "model-download"
        private const val TAG = "ModelDownloadWorker"
        private const val CHANNEL_ID = "model_download"
        private const val NOTIFICATION_ID = 2001

        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_BYTES_TOTAL = "bytes_total"
        const val KEY_ERROR_TYPE = "error_type"
        const val KEY_ERROR_MESSAGE = "error_message"

        fun ensureChannel(context: Context) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.download_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.download_channel_desc)
                    setShowBadge(false)
                }
                mgr.createNotificationChannel(channel)
            }
        }
    }
}

/**
 * Stable string codes for [DownloadError] kinds, transported through WorkManager
 * output data. The UI maps these to PRD §6.2 error states.
 */
enum class DownloadErrorType { NETWORK, HTTP_CLIENT, HTTP_SERVER, STORAGE, CHECKSUM, MISCONFIGURED }

private fun DownloadError.toType(): DownloadErrorType = when (this) {
    is DownloadError.Network -> DownloadErrorType.NETWORK
    is DownloadError.HttpClient -> DownloadErrorType.HTTP_CLIENT
    is DownloadError.HttpServer -> DownloadErrorType.HTTP_SERVER
    is DownloadError.Storage -> DownloadErrorType.STORAGE
    is DownloadError.Checksum -> DownloadErrorType.CHECKSUM
    is DownloadError.Misconfigured -> DownloadErrorType.MISCONFIGURED
}
