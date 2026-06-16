package com.contextsolutions.localagent.app.service

import com.contextsolutions.localagent.agent.ChatSessionController
import com.contextsolutions.localagent.agent.InferenceSession
import com.contextsolutions.localagent.inference.SessionState
import kotlinx.coroutines.flow.StateFlow

/**
 * Android [ChatSessionController] actual — wraps the foreground-service-managed
 * [InferenceSessionManager] + [ModelInventory]. State comes straight from the
 * manager; each turn builds an [InferenceSessionAdapter] bound to the current
 * model path (docs/DESKTOP_PORT_PLAN.md Phase 9).
 */
class AndroidChatSessionController(
    private val sessionManager: InferenceSessionManager,
    private val inventory: ModelInventory,
) : ChatSessionController {
    override val state: StateFlow<SessionState> = sessionManager.state

    override suspend fun newSession(): InferenceSession =
        InferenceSessionAdapter(
            sessionManager = sessionManager,
            modelPath = inventory.localFile().absolutePath,
        )
}
