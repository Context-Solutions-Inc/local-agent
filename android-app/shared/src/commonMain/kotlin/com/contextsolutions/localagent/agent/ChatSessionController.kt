package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.inference.SessionState
import kotlinx.coroutines.flow.StateFlow

/**
 * Cross-platform handle the shared `ChatViewModel` uses to obtain an
 * [InferenceSession] for a turn and to observe model-load state
 * (docs/DESKTOP_PORT_PLAN.md Phase 9).
 *
 * Android wraps the foreground-service-managed `InferenceSessionManager` +
 * `ModelInventory`; desktop wraps the resident warm model. This keeps the
 * ViewModel free of either platform's session lifecycle so it lives in
 * shared `:ui` commonMain.
 */
interface ChatSessionController {
    /** Current model-load state, surfaced as the chat load banner. */
    val state: StateFlow<SessionState>

    /** A session for one agent turn (may suspend while the model loads). */
    suspend fun newSession(): InferenceSession
}
