package com.contextsolutions.mobileagent.inference

import kotlinx.coroutines.flow.Flow

/**
 * The single seam between the agent loop (commonMain) and the on-device LLM runtime
 * (LiteRT-LM, JNI on Android). Every byte of LiteRT-specific code lives behind this
 * interface so churn in the LiteRT-LM API surface (PRD section 8.1) is contained.
 *
 * Phase 1 ships an Android implementation backed by LiteRT-LM. A stub implementation
 * (StubInferenceEngine, in androidApp) drives the spike harness and unit tests until
 * the real runtime is wired in.
 */
interface InferenceEngine {

    /**
     * Loads the model into memory and returns a handle. Blocking; expected 4–8 seconds
     * cold-start on Pixel 7 per the revised perf targets in PHASE1_PLAN.md section 2.3.
     *
     * @param modelPath absolute filesystem path to the .litertmodel artifact
     * @param config tuning knobs (KV cache size, temperature defaults, accelerator preference)
     */
    suspend fun loadModel(modelPath: String, config: InferenceConfig): ModelHandle

    /** Releases model weights and KV cache; safe to call multiple times. */
    fun unload(handle: ModelHandle)

    /**
     * Runs generation. Emits [GenerationEvent.TokenChunk] for each decoded token,
     * [GenerationEvent.FunctionCall] when the model emits a tool call (parser implemented
     * in commonMain), and exactly one terminal event ([GenerationEvent.Done] or
     * [GenerationEvent.Error]).
     *
     * The Flow respects coroutine cancellation — collecting it in a cancelled scope
     * stops generation mid-stream (PRD section 4.1: UI input must never block on output).
     */
    fun generate(handle: ModelHandle, request: GenerationRequest): Flow<GenerationEvent>
}

/** Opaque handle to a loaded model. The runtime owns its lifetime; do not unwrap. */
interface ModelHandle {
    val modelId: String
    val loadedAtEpochMs: Long

    /**
     * The accelerator the engine actually loaded onto. May differ from
     * [InferenceConfig.accelerator] when AUTO falls back (e.g. Pixel 7 without
     * Play Services TFLite → GPU init throws → engine retries on CPU). The UI
     * uses this to show a degraded-mode indicator; telemetry uses it to
     * distinguish GPU vs CPU runs (M0 memo §5 Risk 1).
     */
    val activeAccelerator: Accelerator
}

data class InferenceConfig(
    val kvCacheTokens: Int = 8_192,         // PRD section 4.2
    val accelerator: Accelerator = Accelerator.AUTO,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
)

enum class Accelerator { AUTO, NPU, GPU, CPU }

data class GenerationRequest(
    val prompt: String,
    val maxTokens: Int = 1024,
    val stopSequences: List<String> = emptyList(),
)

sealed interface GenerationEvent {
    data class TokenChunk(val text: String, val tokenIndex: Int) : GenerationEvent
    data class FunctionCall(val name: String, val argumentsJson: String) : GenerationEvent
    data class Done(val totalTokens: Int, val finishReason: FinishReason) : GenerationEvent
    data class Error(val message: String, val cause: Throwable? = null) : GenerationEvent
}

enum class FinishReason { END_OF_TURN, MAX_TOKENS, FUNCTION_CALL, STOP_SEQUENCE, CANCELLED }
