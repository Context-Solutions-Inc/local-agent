package com.contextsolutions.mobileagent.app.spike

import com.contextsolutions.mobileagent.inference.Accelerator
import com.contextsolutions.mobileagent.inference.FinishReason
import com.contextsolutions.mobileagent.inference.GenerationEvent
import com.contextsolutions.mobileagent.inference.GenerationRequest
import com.contextsolutions.mobileagent.inference.InferenceConfig
import com.contextsolutions.mobileagent.inference.InferenceEngine
import com.contextsolutions.mobileagent.inference.ModelHandle
import com.contextsolutions.mobileagent.inference.ToolDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Drop-in stub for [InferenceEngine] used during M0. Emits canned tokens at a
 * configurable rate so the agent loop, UI, and benchmarking harness can be exercised
 * end-to-end before LiteRT-LM and Gemma 4 E4B are wired in.
 *
 * The numbers it simulates default to PHASE1_PLAN.md's revised Pixel 7 targets
 * (8 tok/s sustained, 4s first token, 4-8s cold load) so the harness's calibration
 * matches the real envelope when the real engine slots in.
 *
 * NOT FOR PRODUCTION USE. M1 replaces this binding in Hilt with the real LiteRT-LM
 * engine; this class stays in /spike for ongoing harness work and tests.
 */
class StubInferenceEngine(
    private val simulatedFirstTokenLatencyMs: Long = 4_000,
    private val simulatedTokensPerSecond: Double = 8.0,
    private val simulatedColdLoadMs: Long = 6_000,
    private val cannedResponse: String = DEFAULT_CANNED_RESPONSE,
) : InferenceEngine {

    override suspend fun loadModel(modelPath: String, config: InferenceConfig): ModelHandle {
        delay(simulatedColdLoadMs)
        return StubModelHandle(
            modelId = modelPath,
            loadedAtEpochMs = System.currentTimeMillis(),
            // Stub doesn't actually run on any accelerator — report what was
            // requested (resolving AUTO to GPU to mirror LiteRtInferenceEngine).
            activeAccelerator = if (config.accelerator == Accelerator.AUTO) Accelerator.GPU
            else config.accelerator,
        )
    }

    override fun unload(handle: ModelHandle) {
        // No-op for the stub. The real engine releases LiteRT-LM tensors here.
    }

    override fun generate(
        handle: ModelHandle,
        request: GenerationRequest,
        toolDispatcher: ToolDispatcher?,
    ): Flow<GenerationEvent> = flow {
        delay(simulatedFirstTokenLatencyMs)
        val response = cannedResponse.take(maxResponseChars(request.maxTokens))
        val tokens = response.split(' ').filter { it.isNotEmpty() }
        val perTokenDelayMs = (1_000.0 / simulatedTokensPerSecond).toLong()
        var index = 0
        for (token in tokens) {
            // Append a leading space on every token after the first, matching real BPE output shape.
            val chunk = if (index == 0) token else " $token"
            emit(GenerationEvent.TokenChunk(text = chunk, tokenIndex = index))
            index++
            delay(perTokenDelayMs)
        }
        emit(GenerationEvent.Done(totalTokens = index, finishReason = FinishReason.END_OF_TURN))
    }

    private fun maxResponseChars(maxTokens: Int): Int = (maxTokens * 4).coerceAtMost(cannedResponse.length)

    private data class StubModelHandle(
        override val modelId: String,
        override val loadedAtEpochMs: Long,
        override val activeAccelerator: Accelerator,
    ) : ModelHandle

    companion object {
        // Realistic-shaped response so token-stream UI work has something to render.
        const val DEFAULT_CANNED_RESPONSE: String =
            "This is a stub response from the M0 spike harness. The real LiteRT-LM " +
                "engine emits tokens here at the rate measured on a Pixel 7 with the " +
                "Gemma 4 E4B Q4 model. First-token latency, sustained tokens per second, " +
                "and peak resident memory are recorded by the harness so the M0 decision " +
                "memo can be filled in with actual numbers. Until then, the stub simulates " +
                "the revised Phase 1 perf envelope so downstream UI and agent loop work is " +
                "calibrated against realistic timings."
    }
}
