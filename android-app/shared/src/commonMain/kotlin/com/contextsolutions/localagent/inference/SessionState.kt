package com.contextsolutions.localagent.inference

/**
 * Lifecycle of the on-device LLM session as the chat surface sees it
 * (docs/DESKTOP_PORT_PLAN.md Phase 9). Moved to commonMain so the shared
 * `ChatViewModel`/`ChatScreen` can render a load banner on both platforms;
 * the Android foreground-service manager and the desktop warm-model both
 * drive it through [ChatSessionController].
 */
sealed interface SessionState {
    data object Unloaded : SessionState

    /**
     * The model file is being downloaded (first run). [fraction] is 0..1 when known.
     * Desktop only — the GGUF fetch precedes the first load; the Android download has its
     * own screen. Surfaced on the chat banner so the user knows why generation isn't ready.
     */
    data class Downloading(val fraction: Float?) : SessionState
    data object Loading : SessionState
    data class Loaded(val activeAccelerator: Accelerator) : SessionState
    data class Failed(val message: String, val cause: Throwable?) : SessionState
}
