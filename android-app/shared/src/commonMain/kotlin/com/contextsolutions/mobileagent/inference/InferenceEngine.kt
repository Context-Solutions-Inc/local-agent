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
     */
    suspend fun loadModel(modelPath: String, config: InferenceConfig): ModelHandle

    /** Releases model weights and KV cache; safe to call multiple times. */
    fun unload(handle: ModelHandle)

    /**
     * Runs a complete user-turn generation: the model may emit zero or more
     * tool calls before producing a final assistant message. The engine drives
     * the tool-call cycle internally (one [Conversation][com.google.ai.edge.litertlm.Conversation]
     * across the whole turn, per LiteRT-LM's documented pattern) — when the
     * model emits tool calls, the engine invokes [toolDispatcher] to execute
     * each one and feeds the result back to the SAME conversation. This is
     * what makes Gemma correlate the response with its prior call; replaying
     * the conversation through fresh `initialMessages` on every step does not.
     *
     * Emits [GenerationEvent.TokenChunk] for streamed text and exactly one
     * terminal [GenerationEvent.Done] or [GenerationEvent.Error]. Tool calls
     * are NOT exposed as events; they are dispatched and resolved internally.
     *
     * Cancelling the collector cancels the underlying generation.
     */
    fun generate(
        handle: ModelHandle,
        request: GenerationRequest,
        toolDispatcher: ToolDispatcher? = null,
    ): Flow<GenerationEvent>
}

/**
 * Callback the engine invokes when the model emits a tool call. The agent loop
 * implements this to route to its [com.contextsolutions.mobileagent.search.SearchService],
 * track citations, emit UI events ("Searching: ..."), and enforce per-turn caps.
 *
 * The returned string is fed back to the model as the tool response payload.
 * Errors are not raised: return a short error message string and let the model
 * adapt (PRD §6.2).
 */
fun interface ToolDispatcher {
    suspend fun execute(call: PendingToolCall): String
}

/** A tool call the model has emitted, awaiting execution. */
data class PendingToolCall(
    val name: String,
    val argumentsJson: String,
)

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

/**
 * Two paths supported:
 *  - **Structured (preferred):** [systemInstruction] + [history]. The engine
 *    hands these to LiteRT-LM's `ConversationConfig` so Gemma's chat template
 *    wraps them correctly (`<start_of_turn>user…` etc.). [history] must end
 *    with the message the engine should send via `sendMessageAsync` — typically
 *    a User turn for a fresh question or a Tool turn when continuing after a
 *    tool result. Everything before becomes `initialMessages` on the
 *    conversation. This is what the agent loop produces.
 *  - **Legacy raw:** [prompt] only. Used by the M0 spike harness, which sends
 *    plain test prompts and lets LiteRT-LM template them as a single user turn.
 *
 * The engine picks the structured path when [history] is non-empty.
 *
 * [tools] are registered with LiteRT-LM via `ConversationConfig.tools`. The
 * runtime injects their descriptions into Gemma's chat template in the format
 * the model was trained on — passing a JSON schema in [systemInstruction]
 * text is not enough on its own; the model only treats tools as callable when
 * they come through this channel.
 */
data class GenerationRequest(
    val prompt: String = "",
    val systemInstruction: String? = null,
    val history: List<HistoryMessage> = emptyList(),
    val tools: List<ToolDefinition> = emptyList(),
    val maxTokens: Int = 1024,
    val stopSequences: List<String> = emptyList(),
)

/**
 * One turn in the conversation history fed back into the engine.
 *
 * For [HistoryRole.TOOL], [toolName] should match the tool that was called so
 * the engine can wrap the result as a structured tool response — Gemma needs
 * the correlation to recognise that the call has been answered (otherwise it
 * keeps emitting fresh tool calls until the per-turn cap kicks in).
 *
 * For [HistoryRole.MODEL] with a tool call, [toolCalls] carries the structured
 * call so the engine can build a proper `Message.model(contents, toolCalls)`.
 * Inlining the call as text in [text] doesn't work — Gemma sees only literal
 * `<|tool_call>` characters in that case and never connects the call to the
 * subsequent tool response.
 */
data class HistoryMessage(
    val role: HistoryRole,
    val text: String,
    val toolName: String? = null,
    val toolCalls: List<HistoryToolCall> = emptyList(),
)

/** Structured tool call carried in an assistant [HistoryMessage]. */
data class HistoryToolCall(
    val name: String,
    val argumentsJson: String,
)

enum class HistoryRole { SYSTEM, USER, MODEL, TOOL }

/**
 * Description of a tool we want Gemma to be able to call. [descriptionJson] is
 * the OpenAI/OpenAPI-style function-calling schema (`{name, description,
 * parameters}`); LiteRT-LM hands it to the model via its tool-call channel.
 *
 * We never let LiteRT-LM execute the tool itself — that's what the agent loop
 * does (cache, error mapping, citations). Tool calls come back to us via
 * [GenerationEvent.FunctionCall].
 */
data class ToolDefinition(
    val name: String,
    val descriptionJson: String,
)

sealed interface GenerationEvent {
    data class TokenChunk(val text: String, val tokenIndex: Int) : GenerationEvent
    data class FunctionCall(val name: String, val argumentsJson: String) : GenerationEvent
    data class Done(val totalTokens: Int, val finishReason: FinishReason) : GenerationEvent
    data class Error(val message: String, val cause: Throwable? = null) : GenerationEvent
}

enum class FinishReason { END_OF_TURN, MAX_TOKENS, FUNCTION_CALL, STOP_SEQUENCE, CANCELLED }
