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
    /**
     * Enables Gemma's vision tower at engine init (PR #48). Vision is gated at
     * LiteRT-LM's `EngineConfig` level (`visionBackend` + `maxNumImages`) — it
     * cannot be turned on per-request once the engine is loaded. The chat load
     * path sets this true so an attached photo triggers a vision prefill; the
     * spike harness and unit tests leave it false to avoid paying the
     * vision-init memory/latency cost when no image is ever sent.
     */
    val enableVision: Boolean = false,
)

/**
 * Per-request sampling override. When set on a [GenerationRequest] the engine
 * uses these instead of the load-time [InferenceConfig] sampling values.
 *
 * Used by the agent loop to switch search-grounded (RAG) turns to near-greedy
 * decoding. Under the default temperature 0.7 a 2B model perturbs digit tokens
 * mid-number when copying figures out of a `[SEARCH CONTEXT]` block (observed
 * on-device: a score read as "110" in the context emitted as "1110"). [GREEDY]
 * forces argmax (topK = 1), so the model reproduces the numbers it was given
 * verbatim. Open-chat turns leave [GenerationRequest.sampling] null and keep
 * the warmer defaults.
 */
data class SamplingParams(
    val temperature: Float,
    val topK: Int,
    val topP: Float,
) {
    companion object {
        /**
         * Deterministic argmax for verbatim factual copying. `topK = 1` picks
         * the single highest-probability token, so selection is argmax
         * regardless of temperature. Temperature is kept at a neutral `1.0`
         * (NOT `0.0`) on purpose: LiteRT-LM's `SamplerConfig` exposes no
         * explicit greedy mode, and a `0.0` temperature risks a `logits / temp`
         * divide-by-zero or a "temperature disabled → fall back to stochastic"
         * special-case in the native sampler — either of which would silently
         * defeat greedy decoding. `topK = 1` is the lever; temperature is just
         * held somewhere safe.
         */
        val GREEDY = SamplingParams(temperature = 1.0f, topK = 1, topP = 1.0f)
    }
}

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
    /** When non-null, overrides the engine's load-time sampling. See [SamplingParams]. */
    val sampling: SamplingParams? = null,
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
    /**
     * Optional image attached to a [HistoryRole.USER] turn (PR #48). Holds a
     * downscaled JPEG. Only the *current* (trailing) turn ever carries bytes —
     * image input is ephemeral (PR #48 scope), so prior turns stay null and the
     * image never round-trips through persisted history. The engine wraps these
     * bytes as a `Content.ImageBytes` on the current message.
     *
     * Note: a [ByteArray] member makes this data class's generated
     * `equals`/`hashCode`/`copy` use array identity, not content. Nothing relies
     * on structural equality of [HistoryMessage] today; if that changes, compare
     * this field explicitly.
     */
    val imageBytes: ByteArray? = null,
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
