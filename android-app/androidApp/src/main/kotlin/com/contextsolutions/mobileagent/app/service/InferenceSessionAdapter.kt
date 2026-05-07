package com.contextsolutions.mobileagent.app.service

import com.contextsolutions.mobileagent.agent.InferenceSession
import com.contextsolutions.mobileagent.inference.GenerationEvent
import com.contextsolutions.mobileagent.inference.GenerationRequest
import com.contextsolutions.mobileagent.inference.ToolDispatcher
import kotlinx.coroutines.flow.Flow

/**
 * Adapts the production [InferenceSessionManager] to the agent layer's
 * [InferenceSession] seam. Model path is bound at construction.
 */
class InferenceSessionAdapter(
    private val sessionManager: InferenceSessionManager,
    private val modelPath: String,
) : InferenceSession {
    override fun generate(
        request: GenerationRequest,
        toolDispatcher: ToolDispatcher?,
    ): Flow<GenerationEvent> =
        sessionManager.generate(
            modelPath = modelPath,
            request = request,
            toolDispatcher = toolDispatcher,
        )
}
