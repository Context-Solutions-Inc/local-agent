package com.contextsolutions.localagent.desktop.app

import com.contextsolutions.localagent.inference.DesktopModelDownloader
import com.contextsolutions.localagent.inference.DesktopModelInventory
import com.contextsolutions.localagent.inference.ModelDownloadResult
import com.contextsolutions.localagent.notification.AppNotification
import com.contextsolutions.localagent.notification.NotificationKind
import com.contextsolutions.localagent.notification.NotificationPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    /**
     * Whether this controller fires its own coarse start/complete/fail tray
     * notifications. PR #95: the first-run GGUF + mmproj controllers set this
     * false so a single aggregated "Models ready" notification fires once after
     * ALL first-run models finish, instead of one per model. [status] still
     * drives the chat banner regardless.
     */
    private val notifyMilestones: Boolean = true,
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
        if (!notifyMilestones) return
        notifications.present(
            AppNotification(id = "model-download", title = title, body = body, kind = NotificationKind.INFO),
        )
    }
}

/** A first-run download has reached a terminal state (won't progress further this launch). */
private fun ModelDownloadStatus.isTerminal(): Boolean = when (this) {
    is ModelDownloadStatus.Present,
    is ModelDownloadStatus.NotConfigured,
    is ModelDownloadStatus.Failed,
    -> true
    is ModelDownloadStatus.Idle,
    is ModelDownloadStatus.Downloading,
    -> false
}

/**
 * PR #95 — fire ONE aggregated tray notification once ALL first-run model
 * downloads finish, instead of one per model. The per-controller milestone
 * notifications are suppressed (`notifyMilestones = false`) and replaced by this
 * single completion notice. Only notifies when at least one source actually
 * downloaded this launch — a returning user whose models are all already present
 * gets no spurious toast. Emits once, then stops observing. Routes through the
 * shared [NotificationPresenter] (notify-send on Linux, tray toast elsewhere).
 */
fun notifyWhenAllModelsDownloaded(
    sources: List<StateFlow<ModelDownloadStatus>>,
    notifications: NotificationPresenter,
    scope: CoroutineScope,
) {
    if (sources.isEmpty()) return
    scope.launch {
        var sawDownloading = false
        val terminal = combine(sources) { it.toList() }.first { statuses ->
            if (statuses.any { it is ModelDownloadStatus.Downloading }) sawDownloading = true
            statuses.all { it.isTerminal() }
        }
        if (!sawDownloading) return@launch // nothing downloaded this launch — stay silent
        val failures = terminal.count { it is ModelDownloadStatus.Failed }
        val notice = if (failures == 0) {
            AppNotification(
                id = "model-download",
                title = "Models ready",
                body = "All models downloaded.",
                kind = NotificationKind.INFO,
            )
        } else {
            AppNotification(
                id = "model-download",
                title = "Model download incomplete",
                body = "$failures download${if (failures == 1) "" else "s"} failed.",
                kind = NotificationKind.INFO,
            )
        }
        notifications.present(notice)
    }
}
