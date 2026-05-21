package com.contextsolutions.mobileagent.inference

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Production [InferenceEngine] backed by LiteRT-LM. The single seam between the
 * agent loop (commonMain) and LiteRT-LM lives here — every LiteRT-LM type stays
 * inside this file, per PHASE1_PLAN §3.3.
 *
 * Threading: LiteRT-LM is internally synchronous and exposes its async surface
 * via Kotlin Flow, which this implementation forwards directly. Cancelling the
 * collecting coroutine cancels the underlying generation.
 *
 * Lifecycle: [loadModel] holds an [Engine] for the lifetime of the returned
 * [ModelHandle]; [unload] closes it. A fresh [com.google.ai.edge.litertlm.Conversation]
 * is created per [generate] call (use {} closes it on completion or error).
 *
 * Phase 1 limitations:
 * - Function-call parsing is not yet wired. LiteRT-LM 0.10.2's ConversationConfig
 *   exposes a `tools` list and `automaticToolCalling` flag — when M3's pre-flight
 *   + tool-calling work lands, those plug in here and emit [GenerationEvent.FunctionCall].
 * - [GenerationRequest.stopSequences] is not yet honored — no stop-sequence
 *   surface on ConversationConfig. Generation runs until end-of-turn.
 * - [GenerationRequest.maxTokens] is also not yet honored as a per-request cap;
 *   LiteRT-LM only exposes a model-level [EngineConfig.maxNumTokens] (see
 *   [InferenceConfig.kvCacheTokens] mapping).
 */
class LiteRtInferenceEngine(private val context: Context) : InferenceEngine {

    /**
     * Every blocking native call below MUST run off the main thread:
     * [Engine.initialize] alone takes 4–8s cold on Pixel 7, well past Android's
     * 5s ANR threshold. The previous version blocked the UI thread and was killed
     * by the OS as "not responding" before the model finished loading.
     *
     * loadModel + unload are wrapped in withContext(IO); generate's Flow uses
     * flowOn(IO) so both upstream emission AND downstream collect happen off main.
     */
    override suspend fun loadModel(
        modelPath: String,
        config: InferenceConfig,
    ): ModelHandle = withContext(Dispatchers.IO) {
        // WARNING level keeps Logcat clean in production; bump to INFO/VERBOSE
        // when debugging perf, accelerator selection, or chat-template emission.
        Engine.setNativeMinLogSeverity(LogSeverity.WARNING)

        val requested = resolveAccelerator(config.accelerator)
        val (engine, actual) = tryInitialize(modelPath, config, requested)

        LiteRtModelHandle(
            modelId = modelPath,
            loadedAtEpochMs = System.currentTimeMillis(),
            engine = engine,
            config = config,
            activeAccelerator = actual,
        )
    }

    /**
     * Initialise an [Engine] on [requested]. If the requested accelerator is GPU
     * and was selected via [Accelerator.AUTO] (i.e. caller didn't explicitly pin
     * GPU), and initialise() throws, retry on CPU.
     *
     * GPU init can fail at runtime even when our deps are correct — Play Services
     * TFLite isn't on every device (CN market, AOSP forks, GrapheneOS) and is the
     * source of `Cannot find OpenCL library on this device` (M0 memo §5 Risk 1).
     * CPU isn't fast enough for the relaxed Phase 1 perf targets but degraded
     * generation is strictly better than a hard load failure with no chat.
     *
     * Explicit [Accelerator.GPU] / [Accelerator.NPU] do NOT fall back — the spike
     * harness uses those to characterise specific accelerators and a silent
     * fallback would falsify its measurements.
     */
    private fun tryInitialize(
        modelPath: String,
        config: InferenceConfig,
        requested: Accelerator,
    ): Pair<Engine, Accelerator> {
        val first = newEngine(modelPath, config, requested)
        try {
            first.initialize()
            return first to requested
        } catch (t: Throwable) {
            // Always close the half-initialised engine before retrying — leaving
            // it open holds native handles that the CPU retry will collide with.
            runCatching { first.close() }
            if (config.accelerator != Accelerator.AUTO || requested == Accelerator.CPU) {
                throw t
            }
            android.util.Log.w(
                TAG,
                "GPU init failed (${t.message}); falling back to CPU. Generation will be slower.",
            )
            val fallback = newEngine(modelPath, config, Accelerator.CPU)
            try {
                fallback.initialize()
            } catch (t2: Throwable) {
                runCatching { fallback.close() }
                t2.addSuppressed(t)
                throw t2
            }
            return fallback to Accelerator.CPU
        }
    }

    private fun newEngine(modelPath: String, config: InferenceConfig, accelerator: Accelerator): Engine {
        val engineConfig = EngineConfig(
            modelPath = modelPath,
            backend = backendFor(accelerator),
            // PRD §4.2 sizes the KV cache for 8K-token contexts; LiteRT-LM exposes
            // this as a model-level cap on prefill + decode tokens.
            maxNumTokens = config.kvCacheTokens,
            // GPU delegate caches compiled kernels here; reused across loads to
            // avoid the multi-second recompile on every cold start.
            cacheDir = context.cacheDir.absolutePath,
        )
        return Engine(engineConfig)
    }

    override fun unload(handle: ModelHandle) {
        // Synchronous because the InferenceEngine contract is fire-and-forget for
        // unload, but the actual native call still blocks. Callers should invoke
        // from a background coroutine; see SpikeRunner which already does so via
        // its withContext(IO) wrapper.
        val typed = handle as? LiteRtModelHandle ?: return
        typed.engine.close()
    }

    override fun generate(
        handle: ModelHandle,
        request: GenerationRequest,
        toolDispatcher: ToolDispatcher?,
    ): Flow<GenerationEvent> = flow {
        val typed = handle as? LiteRtModelHandle
            ?: error("ModelHandle is not a LiteRtModelHandle; engine binding is wrong.")

        val samplerConfig = SamplerConfig(
            topK = typed.config.topK,
            topP = typed.config.topP.toDouble(),
            temperature = typed.config.temperature.toDouble(),
            seed = (System.nanoTime() and 0x7FFFFFFF).toInt(),
        )

        val isStructured = request.history.isNotEmpty()

        // Legacy spike-harness path: one prompt, no tools. Keep it minimal.
        if (!isStructured) {
            val convoConfig = ConversationConfig(samplerConfig = samplerConfig)
            var tokenIndex = 0
            val conversation = typed.engine.createConversation(convoConfig)
            // Wire coroutine cancellation to the native abort primitive: without
            // this, Job.cancel() only detaches the Kotlin flow — LiteRT-LM's
            // worker thread keeps decoding for the rest of the turn, holding GPU
            // and freezing the UI. cancelProcess() tells the native side to stop
            // the in-flight decode immediately. See PR #22 root cause.
            val cancelHandle = bindCancellation(conversation)
            try {
                conversation.sendMessageAsync(request.prompt)
                    .catch { t -> emit(GenerationEvent.Error(t.message ?: "unknown error", t)) }
                    .collect { chunk ->
                        val text = chunk.contents.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString("") { it.text }
                        if (text.isNotEmpty()) {
                            emit(GenerationEvent.TokenChunk(text, tokenIndex))
                            tokenIndex++
                        }
                    }
            } finally {
                cancelHandle?.dispose()
                runCatching { conversation.cancelProcess() }
                runCatching { conversation.close() }
            }
            emit(GenerationEvent.Done(tokenIndex, FinishReason.END_OF_TURN))
            return@flow
        }

        // Structured path: ONE conversation per user turn, drive sendMessageAsync
        // multiple times on it. Per https://ai.google.dev/edge/litert-lm/android,
        // reusing the same Conversation is what lets the model correlate a tool
        // response with the prior call. Re-creating the conversation each step
        // and replaying via initialMessages does not.
        val current = request.history.last()
        val initialMessages = request.history.dropLast(1).map { it.toLiteRtMessage() }
        val convoConfig = ConversationConfig(
            systemInstruction = request.systemInstruction
                ?.takeIf { it.isNotBlank() }
                ?.let { Contents.of(it) }
                ?: Contents.of(""),
            initialMessages = initialMessages,
            tools = request.tools.map { it.toLiteRtToolProvider() },
            samplerConfig = samplerConfig,
            automaticToolCalling = false,
        )

        android.util.Log.i(
            "LiteRtEngine",
            "createConversation toolsRegistered=" +
                request.tools.joinToString(",") { it.name } +
                " systemPromptLen=${request.systemInstruction?.length ?: 0}",
        )
        val conversation = typed.engine.createConversation(convoConfig)
        // Wire coroutine cancellation to LiteRT-LM's native abort primitive.
        // Without this, ChatViewModel.cancel() → Job.cancel() only detaches
        // the Kotlin flow — the native decode loop keeps running on
        // LiteRT-LM's worker thread until end-of-turn, holding the GPU and
        // making the app feel unresponsive. cancelProcess() asks the native
        // side to stop immediately; the upstream Flow then completes and the
        // finally block tears the conversation down. See PR #22 root cause.
        val cancelHandle = bindCancellation(conversation)
        var tokenIndex = 0
        try {
            // First leg: send the current message (typically a User string;
            // could also be a Tool turn if the agent loop is resuming after
            // an external interrupt — not used in the current production path).
            var nextMessage: Any = current.toCurrentMessage()

            stepLoop@ while (true) {
                currentCoroutineContext().ensureActive()
                val pendingCalls = mutableMapOf<String, com.google.ai.edge.litertlm.ToolCall>()
                val flow = when (nextMessage) {
                    is String -> conversation.sendMessageAsync(nextMessage)
                    is Message -> conversation.sendMessageAsync(nextMessage)
                    else -> error("unexpected outbound type: ${nextMessage::class}")
                }
                var failed = false
                flow.catch { t ->
                    failed = true
                    emit(GenerationEvent.Error(t.message ?: "unknown error", t))
                }.collect { chunk ->
                    chunk.toolCalls.forEach { call ->
                        // Each chunk often carries the cumulative toolCalls list —
                        // dedup by name+args so each logical call only runs once.
                        val argsJson = serializeToolArguments(call.arguments)
                        val key = "${call.name}|$argsJson"
                        pendingCalls.putIfAbsent(key, call)
                    }
                    val text = chunk.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString("") { it.text }
                    if (text.isNotEmpty()) {
                        emit(GenerationEvent.TokenChunk(text, tokenIndex))
                        tokenIndex++
                    }
                }
                if (failed) return@flow

                if (pendingCalls.isEmpty() || toolDispatcher == null) break@stepLoop

                // Execute tools, build a tool-response message, send it back to
                // the SAME conversation. Gemma's correlation depends on this.
                // ensureActive before each dispatch so a cancel during a slow
                // tool (web search) bails before we round-trip back to Gemma.
                val responses = pendingCalls.values.map { call ->
                    currentCoroutineContext().ensureActive()
                    val argsJson = serializeToolArguments(call.arguments)
                    val responseText = toolDispatcher.execute(
                        PendingToolCall(name = call.name, argumentsJson = argsJson),
                    )
                    val structured = parseAsStructured(responseText) ?: responseText
                    Content.ToolResponse(call.name, structured)
                }
                nextMessage = Message.tool(Contents.of(responses))
            }
        } finally {
            cancelHandle?.dispose()
            runCatching { conversation.cancelProcess() }
            runCatching { conversation.close() }
        }
        emit(GenerationEvent.Done(tokenIndex, FinishReason.END_OF_TURN))
    }.flowOn(Dispatchers.IO)

    /**
     * Bridge Kotlin coroutine cancellation to LiteRT-LM's native
     * [com.google.ai.edge.litertlm.Conversation.cancelProcess].
     *
     * `Conversation.sendMessageAsync(...)` returns a Flow that wraps a native
     * decode loop. Cancelling the Kotlin Job alone closes the channel but
     * leaves the native worker computing tokens until end-of-turn —
     * symptomatically, the user taps Cancel, the UI clears the bubble, then
     * the device stutters for several seconds while LiteRT-LM finishes the
     * response no one is listening to. `cancelProcess()` is the documented
     * primitive on `Conversation` (LiteRT-LM 0.10.2) for asking the native
     * side to abort. Returns the [kotlinx.coroutines.DisposableHandle] so the
     * finally block can detach the listener on normal completion (otherwise
     * the handle would fire on every coroutine end and run a redundant
     * cancelProcess + leak the handle reference).
     */
    private suspend fun bindCancellation(
        conversation: com.google.ai.edge.litertlm.Conversation,
    ): kotlinx.coroutines.DisposableHandle? {
        val job = currentCoroutineContext()[Job] ?: return null
        return job.invokeOnCompletion { cause ->
            if (cause != null) {
                runCatching { conversation.cancelProcess() }
            }
        }
    }

    /**
     * The "current" message for sendMessageAsync. For a User turn we return a
     * raw String (matches LiteRT-LM's String overload of sendMessageAsync); for
     * a Tool turn we return a Message so the response carries the structured
     * Content.ToolResponse + the tool name. Other roles fall back to the
     * generic Message conversion.
     */
    private fun HistoryMessage.toCurrentMessage(): Any = when (role) {
        HistoryRole.USER -> text
        else -> toLiteRtMessage()
    }

    private fun HistoryMessage.toLiteRtMessage(): Message = when (role) {
        HistoryRole.SYSTEM -> Message.system(text)
        HistoryRole.USER -> Message.user(text)
        HistoryRole.MODEL -> {
            if (toolCalls.isNotEmpty()) {
                // Gemma's chat template only correlates a tool response with
                // its prior call when the call is structured (Message.toolCalls
                // populated). Inlining the call as text in the contents leaves
                // the response orphaned and the model just refuses to use it.
                val liteRtCalls = toolCalls.map { it.toLiteRtToolCall() }
                Message.model(Contents.of(text), liteRtCalls, emptyMap())
            } else {
                Message.model(text)
            }
        }
        HistoryRole.TOOL -> {
            // Tool turns must use Content.ToolResponse so Gemma can correlate
            // the result with the prior tool call. Plain text content makes the
            // model think the call was never answered and it keeps emitting
            // fresh tool calls until the agent's per-turn cap fires.
            //
            // The `response` payload also has to be a STRUCTURED Java type
            // (Map/List/primitive) — LiteRT-LM's JsonConvertersKt.toJsonElement
            // wraps a String as a JSON-quoted string ("response": "[{...}]"),
            // not as the JSON array/object the model expects. So we parse our
            // payload back into Kotlin maps/lists before handing it over.
            val name = toolName
                ?: error("HistoryMessage(role=TOOL) requires a toolName so the engine can wrap it as Content.ToolResponse")
            val structured = parseAsStructured(text) ?: text
            Message.tool(Contents.of(Content.ToolResponse(name, structured)))
        }
    }

    private fun HistoryToolCall.toLiteRtToolCall(): com.google.ai.edge.litertlm.ToolCall {
        @Suppress("UNCHECKED_CAST")
        val args: Map<String, Any?> = (parseAsStructured(argumentsJson) as? Map<String, Any?>) ?: emptyMap()
        return com.google.ai.edge.litertlm.ToolCall(name, args)
    }

    private fun parseAsStructured(jsonText: String): Any? = try {
        kotlinJson.parseToJsonElement(jsonText).toJavaStructured()
    } catch (_: Throwable) {
        null
    }

    private fun JsonElement.toJavaStructured(): Any? = when (this) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            isString -> content
            booleanOrNull != null -> booleanOrNull
            longOrNull != null -> longOrNull
            doubleOrNull != null -> doubleOrNull
            else -> content
        }
        is JsonArray -> map { it.toJavaStructured() }
        is JsonObject -> entries.associate { it.key to it.value.toJavaStructured() }
    }

    private val kotlinJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Adapts our [ToolDefinition] to LiteRT-LM's [OpenApiTool]. The
     * [OpenApiTool.execute] callback is a no-op — `automaticToolCalling`
     * is false, so LiteRT-LM forwards calls to us via the streamed
     * [Message.toolCalls] instead of running them itself.
     */
    private fun ToolDefinition.toLiteRtToolProvider(): com.google.ai.edge.litertlm.ToolProvider {
        val descriptor: OpenApiTool = object : OpenApiTool {
            override fun getToolDescriptionJsonString(): String = descriptionJson
            override fun execute(paramsJsonString: String): String = ""
        }
        return tool(descriptor)
    }

    /**
     * Renders a [com.google.ai.edge.litertlm.ToolCall.arguments] map to a
     * compact JSON object. The agent-loop side parses it back to extract
     * named arguments (e.g. `query`).
     */
    private fun serializeToolArguments(args: Map<String, Any?>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((key, value) in args) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(escapeJsonString(key)).append("\":")
            sb.append(jsonValue(value))
        }
        sb.append('}')
        return sb.toString()
    }

    private fun jsonValue(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> v.toString()
        is Number -> v.toString()
        is String -> "\"" + escapeJsonString(v) + "\""
        else -> "\"" + escapeJsonString(v.toString()) + "\""
    }

    private fun escapeJsonString(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    /**
     * Maps [Accelerator.AUTO] to the concrete first-choice for the device.
     * Pixel 7 default is GPU (Mali-G710); NPU support for Tensor G2 EdgeTPU via
     * LiteRT-LM is unconfirmed (PHASE1_PLAN §6 risk). The CPU fallback in
     * [tryInitialize] handles the case where GPU init throws at runtime.
     */
    private fun resolveAccelerator(accelerator: Accelerator): Accelerator = when (accelerator) {
        Accelerator.AUTO -> Accelerator.GPU
        else -> accelerator
    }

    private fun backendFor(accelerator: Accelerator): Backend = when (accelerator) {
        // null lets LiteRT-LM pick a sensible thread count (typically num cores).
        Accelerator.CPU -> Backend.CPU(/* numOfThreads = */ null)
        Accelerator.GPU -> Backend.GPU()
        Accelerator.NPU -> Backend.NPU(
            nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
        )
        // AUTO is resolved to a concrete accelerator before we pick a backend;
        // hitting this branch is a programming error.
        Accelerator.AUTO -> error("AUTO must be resolved before calling backendFor")
    }

    private data class LiteRtModelHandle(
        override val modelId: String,
        override val loadedAtEpochMs: Long,
        override val activeAccelerator: Accelerator,
        val engine: Engine,
        val config: InferenceConfig,
    ) : ModelHandle

    private companion object {
        const val TAG = "LiteRtInferenceEngine"
    }
}

/**
 * Hilt provides a [LiteRtInferenceEngine] via this factory because [Context] can't
 * be passed through the [InferenceEngine] interface (which lives in commonMain
 * and can't reference Android types). Same pattern as [com.contextsolutions.mobileagent.platform.SecureStorageFactory].
 */
object LiteRtInferenceEngineFactory {
    fun create(context: Context): InferenceEngine = LiteRtInferenceEngine(context.applicationContext)
}
