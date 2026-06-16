package com.contextsolutions.localagent.desktop.app

import com.contextsolutions.localagent.agent.ChatSessionController
import com.contextsolutions.localagent.agent.InferenceSession
import com.contextsolutions.localagent.inference.Accelerator
import com.contextsolutions.localagent.inference.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** One first-run download to reflect on the banner: its known total size + live status. */
class DownloadSource(val totalBytes: Long, val status: StateFlow<ModelDownloadStatus>)

/**
 * Desktop [ChatSessionController] actual (docs/DESKTOP_PORT_PLAN.md Phase 9 inc
 * 8d) — wraps the resident [WarmModel]. The Android side gates residency behind a
 * foreground service + watchdog; desktop simply keeps the model loaded once
 * warmed (it loads lazily on the first turn). [state] transitions
 * Unloaded → Loading → Loaded around that first [newSession], reporting the
 * accelerator the resident handle exposes ([WarmModel.activeAccelerator]) — GPU
 * for a GPU-capable native + AUTO/GPU config, CPU otherwise. The value is
 * best-effort (derived from the requested GPU-layer count, not a real probe —
 * accurate detection is Phase 8); falls back to [Accelerator.CPU] only if the
 * handle hasn't surfaced one.
 */
class DesktopChatSessionController(
    private val warmModel: WarmModel,
) : ChatSessionController {

    private val _state = MutableStateFlow<SessionState>(SessionState.Unloaded)
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    override suspend fun newSession(): InferenceSession = load()

    /**
     * Reflect the first-run downloads into the banner: while the GGUF, the vision projector
     * (mmproj), and the llama-server binary are being fetched, [state] reports
     * [SessionState.Downloading] with an **aggregate** progress (bytes across all [sources]),
     * so the user sees "Downloading model… N%" — not "next prompt cold-loads" — and it only
     * reaches done when every piece is present. Wired by [Main]. Only touches [state] before
     * a load begins; never clobbers Loading/Loaded, which the load path owns.
     */
    fun bindDownloads(sources: List<DownloadSource>, scope: CoroutineScope) {
        if (sources.isEmpty()) return
        scope.launch {
            combine(sources.map { it.status }) { it.toList() }.collect { statuses ->
                val cur = _state.value
                if (cur is SessionState.Loading || cur is SessionState.Loaded) return@collect

                val anyDownloading = statuses.any { it is ModelDownloadStatus.Downloading }
                val failed = statuses.firstNotNullOfOrNull { it as? ModelDownloadStatus.Failed }
                val totalAll = sources.sumOf { it.totalBytes }
                val doneAll = sources.indices.sumOf { i ->
                    when (val s = statuses[i]) {
                        is ModelDownloadStatus.Downloading -> s.downloadedBytes
                        is ModelDownloadStatus.Present -> sources[i].totalBytes
                        else -> 0L
                    }
                }
                _state.value = when {
                    anyDownloading ->
                        SessionState.Downloading(if (totalAll > 0L) doneAll.toFloat() / totalAll else null)
                    failed != null ->
                        SessionState.Failed("Model download failed: ${failed.message}", null)
                    else -> SessionState.Unloaded
                }
            }
        }
    }

    /**
     * Eagerly load the GGUF so the first turn doesn't pay the multi-second
     * cold-load — desktop loads to the GPU at startup ([Main]), while Android
     * keeps Gemma lazy (loads on first generate, invariant #22). Drives [state]
     * Unloaded → Loading → Loaded so the chat banner reflects it. Idempotent
     * (a no-op once loaded) and swallows failures — [state] already carries the
     * [SessionState.Failed], so a background startup warm can't crash the app.
     */
    suspend fun warmUp() {
        if (_state.value is SessionState.Loaded) return
        runCatching { load() }
    }

    private suspend fun load(): InferenceSession {
        if (_state.value !is SessionState.Loaded) _state.value = SessionState.Loading
        return try {
            val session = warmModel.session()
            _state.value = SessionState.Loaded(warmModel.activeAccelerator ?: Accelerator.CPU)
            session
        } catch (t: Throwable) {
            _state.value = SessionState.Failed(t.message ?: "model load failed", t)
            throw t
        }
    }
}
