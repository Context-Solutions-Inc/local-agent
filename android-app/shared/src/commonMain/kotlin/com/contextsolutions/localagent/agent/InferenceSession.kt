package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.inference.GenerationEvent
import com.contextsolutions.localagent.inference.GenerationRequest
import com.contextsolutions.localagent.inference.ToolDispatcher
import kotlinx.coroutines.flow.Flow

/**
 * Thin seam between the agent loop and the inference engine. The Android
 * implementation is a one-line wrapper around `InferenceSessionManager.generate`
 * that closes over the production model path; tests substitute an in-memory
 * fake without going through Hilt.
 *
 * The engine drives the multi-step tool-call cycle internally on a single
 * LiteRT-LM Conversation; [toolDispatcher] is invoked when the model emits a
 * tool call and the returned string is fed back as the response.
 */
interface InferenceSession {
    fun generate(
        request: GenerationRequest,
        toolDispatcher: ToolDispatcher? = null,
    ): Flow<GenerationEvent>
}
