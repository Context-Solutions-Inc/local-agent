package com.contextsolutions.mobileagent.desktop.app

import com.contextsolutions.mobileagent.inference.DesktopModelDownloader
import com.contextsolutions.mobileagent.inference.DesktopModelInventory
import com.contextsolutions.mobileagent.inference.ModelDownloadResult
import com.contextsolutions.mobileagent.notification.AppNotification
import com.contextsolutions.mobileagent.notification.NotificationKind
import com.contextsolutions.mobileagent.notification.NotificationPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Live state of the desktop GGUF acquisition (docs/DESKTOP_PORT_PLAN.md, Phase 7). */
sealed interface ModelDownloadStatus {
    /** Not yet checked. */
    data object Idle : ModelDownloadStatus

    /** The model file is present + verified — ready to load. */
    data object Present : ModelDownloadStatus

    /**
     * The bundled spec has no URL/sha256/size yet (the operator must fill
     * `DesktopModelInventory.DEFAULT`, the BYO-model analogue of Android's
     * `secrets.properties`). No download is attempted.
     */
    data object NotConfigured : ModelDownloadStatus

    data class Downloading(val fraction: Float, val downloadedBytes: Long, val totalBytes: Long) : ModelDownloadStatus

    data class Failed(val message: String, val retryable: Boolean) : ModelDownloadStatus
}

/**
 * Drives the Phase-4 [DesktopModelDownloader] from the desktop app and surfaces
 * progress (docs/DESKTOP_PORT_PLAN.md, Phase 7 — model download via tray),
 * replacing Android's WorkManager `ModelDownloadWorker`. [status] feeds the
 * window's status line; coarse milestones (start / complete / fail) also fire
 * tray notifications through the shared [NotificationPresenter].
 *
 * [ensurePresent] is a no-op when the model is already downloaded, and reports
 * [ModelDownloadStatus.NotConfigured] (without attempting a fetch) when the spec
 * is blank — so a fresh checkout / CI run neither downloads nor errors.
 */
class DesktopModelDownloadController(
    private val inventory: DesktopModelInventory,
    private val downloader: DesktopModelDownloader,
    private val notifications: NotificationPresenter,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = { System.err.println("[ModelDownload] $it") },
) {
    private val _status = MutableStateFlow<ModelDownloadStatus>(ModelDownloadStatus.Idle)
    val status: StateFlow<ModelDownloadStatus> = _status.asStateFlow()

    private var job: Job? = null

    /** Ensure the model is present, downloading it in the background if needed. Idempotent. */
    fun ensurePresent() {
        if (job?.isActive == true) return
        if (inventory.isPresent()) {
            _status.value = ModelDownloadStatus.Present
            return
        }
        if (!inventory.spec.isConfigured) {
            _status.value = ModelDownloadStatus.NotConfigured
            logger("model spec not configured — fill DesktopModelInventory.DEFAULT to enable download")
            return
        }
        job = scope.launch {
            notify("Downloading model", "Starting…")
            // Throttle progress-driven StateFlow writes to whole-percent steps so
            // the UI doesn't churn on every 64 KB chunk.
            var lastPct = -1
            val result = downloader.download { done, total ->
                val fraction = if (total > 0L) done.toFloat() / total else 0f
                val pct = (fraction * 100).toInt()
                if (pct != lastPct) {
                    lastPct = pct
                    _status.value = ModelDownloadStatus.Downloading(fraction, done, total)
                }
            }
            when (result) {
                is ModelDownloadResult.Success -> {
                    _status.value = ModelDownloadStatus.Present
                    notify("Model ready", "Download complete.")
                }
                is ModelDownloadResult.Failure -> {
                    _status.value = ModelDownloadStatus.Failed(result.message, result.retryable)
                    notify("Model download failed", result.message)
                    logger("download failed (retryable=${result.retryable}): ${result.message}")
                }
            }
        }
    }

    /** Retry after a failure (no-op while a download is in flight). */
    fun retry() = ensurePresent()

    private fun notify(title: String, body: String) {
        notifications.present(
            AppNotification(id = "model-download", title = title, body = body, kind = NotificationKind.INFO),
        )
    }
}
