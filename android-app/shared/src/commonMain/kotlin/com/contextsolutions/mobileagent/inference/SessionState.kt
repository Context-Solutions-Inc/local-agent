package com.contextsolutions.mobileagent.inference

/**
 * Lifecycle of the on-device LLM session as the chat surface sees it
 * (docs/DESKTOP_PORT_PLAN.md Phase 9). Moved to commonMain so the shared
 * `ChatViewModel`/`ChatScreen` can render a load banner on both platforms;
 * the Android foreground-service manager and the desktop warm-model both
 * drive it through [ChatSessionController].
 */
sealed interface SessionState {
    data object Unloaded : SessionState
    data object Loading : SessionState
    data class Loaded(val activeAccelerator: Accelerator) : SessionState
    data class Failed(val message: String, val cause: Throwable?) : SessionState
}
