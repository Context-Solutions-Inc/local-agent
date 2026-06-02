package com.contextsolutions.mobileagent.app.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * UI-facing API for the model download. Wraps WorkManager's flow surface so the
 * rest of the app doesn't need to know what a [WorkInfo] is.
 *
 * Pause/resume semantics (PRD §3.5):
 *  - [start] enqueues with [ExistingWorkPolicy.KEEP] — repeated calls while a
 *    download is already active are no-ops, so the UI can call this freely.
 *  - [pause] cancels the active work; the `.partial` file remains on disk.
 *  - [resume] enqueues again; the Worker reads the partial and continues from
 *    the existing offset via the HTTP `Range` header.
 *  - [retry] is the same as resume but conceptually distinct — used after a
 *    terminal failure rather than a user pause.
 *
 * Metered networks (PRD §3.5: "the app must require explicit user confirmation
 * before downloading"): default [start] uses [NetworkType.UNMETERED]. The UI
 * surfaces a confirmation dialog and re-calls [start] with `allowMetered=true`
 * when the user opts in.
 */
class ModelDownloadController(
    private val context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    /**
     * Application-scoped — the controller is a singleton that outlives the UI.
     * `Eagerly` keeps the WorkManager subscription open for the process lifetime
     * so we don't drop progress updates while no UI is currently observing.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val state: StateFlow<DownloadState> = workManager
        .getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.UNIQUE_WORK_NAME)
        .map { infos -> infos.firstOrNull().toDownloadState() }
        .stateIn(scope, SharingStarted.Eagerly, DownloadState.Idle)

    /** Begins (or no-ops if already running) the download. */
    fun start(allowMetered: Boolean = false) {
        val networkType = if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(constraints)
            // 30 s base × 2^attempt up to a 5-hour cap. Plenty of headroom for a
            // flaky network without burning device cycles in tight loops.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            ModelDownloadWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** Cancels the active work. The `.partial` file is intentionally preserved. */
    fun pause() {
        workManager.cancelUniqueWork(ModelDownloadWorker.UNIQUE_WORK_NAME)
    }

    /** Re-enqueues after a [pause]. Picks up from the existing partial file. */
    fun resume(allowMetered: Boolean = false) = start(allowMetered)

    /** User-driven retry after a terminal failure. Same effect as [resume]. */
    fun retry(allowMetered: Boolean = false) = start(allowMetered)

    private fun WorkInfo?.toDownloadState(): DownloadState {
        if (this == null) return DownloadState.Idle
        return when (state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadState.Queued
            WorkInfo.State.RUNNING -> {
                val downloaded = progress.getLong(ModelDownloadWorker.KEY_BYTES_DOWNLOADED, 0L)
                val total = progress.getLong(ModelDownloadWorker.KEY_BYTES_TOTAL, 0L)
                DownloadState.Running(downloaded, total)
            }
            WorkInfo.State.SUCCEEDED -> DownloadState.Completed
            WorkInfo.State.FAILED -> {
                val typeName = outputData.getString(ModelDownloadWorker.KEY_ERROR_TYPE)
                val message = outputData.getString(ModelDownloadWorker.KEY_ERROR_MESSAGE)
                    ?: "Unknown error"
                DownloadState.Failed(
                    type = typeName?.let { runCatching { DownloadErrorType.valueOf(it) }.getOrNull() },
                    message = message,
                )
            }
            WorkInfo.State.CANCELLED -> DownloadState.Idle
        }
    }
}

sealed interface DownloadState {
    data object Idle : DownloadState
    data object Queued : DownloadState
    data class Running(val bytesDownloaded: Long, val bytesTotal: Long) : DownloadState
    data object Completed : DownloadState
    data class Failed(val type: DownloadErrorType?, val message: String) : DownloadState
}
