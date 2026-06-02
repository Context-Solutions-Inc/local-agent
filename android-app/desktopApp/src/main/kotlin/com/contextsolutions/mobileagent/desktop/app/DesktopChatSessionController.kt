package com.contextsolutions.mobileagent.desktop.app

import com.contextsolutions.mobileagent.agent.ChatSessionController
import com.contextsolutions.mobileagent.agent.InferenceSession
import com.contextsolutions.mobileagent.inference.Accelerator
import com.contextsolutions.mobileagent.inference.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
