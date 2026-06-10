package com.contextsolutions.mobileagent.inference

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Desktop [InferenceEngine] backed by a local `llama-server` subprocess over HTTP
 * (docs/DESKTOP_LLAMA_SERVER_PLAN.md, PR #55 Option 3). Replaces the JNI
 * `LlamaCppInferenceEngine` (`net.ladenthin:llama`), which dropped images before the vision
 * encoder and shipped CPU-only natives. The agent core ([InferenceEngine]) is unchanged;
 * only this desktop seam swaps JNI for HTTP. Android keeps LiteRT-LM.
 *
 * `loadModel` ensures the prebuilt binary ([LlamaServerBinaryStore]), starts the server
 * ([LlamaServerProcess]) with the GGUF + (when vision) the mmproj, and waits for `/health`.
 * `generate` streams `POST /v1/chat/completions` (SSE). An image turn (trailing USER
 * `imageBytes`) is sent as a multipart `content` array with an `image_url` data-URI — the
 * canonical, *working* multimodal path: llama-server runs it through `mtmd`.
 */
class LlamaServerInferenceEngine(
    private val binaryStore: LlamaServerBinaryStore,
    /** Resolves the mmproj path (null ⇒ no vision); consulted only when `enableVision`. */
    private val mmprojPathProvider: () -> String? = { null },
    /** Resolves the Vulkan device pin (null ⇒ all GPUs); consulted only on the GPU variant (#78). */
    private val devicePinProvider: () -> String? = { null },
    private val defaultTemperature: Float = InferenceConfig().temperature,
    private val logger: (String) -> Unit = { System.err.println("[LlamaServer] $it") },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : InferenceEngine {

    override suspend fun loadModel(modelPath: String, config: InferenceConfig): ModelHandle =
        withContext(ioDispatcher) {
            val mmproj = if (config.enableVision) mmprojPathProvider() else null
            if (config.enableVision && mmproj == null) {
                logger("vision requested but no mmproj on disk — starting text-only")
            }
            val forced = forcedVariant()
            val wantGpu = when (forced) {
                Variant.CPU -> false
                Variant.GPU -> true
                else -> config.accelerator == Accelerator.AUTO || config.accelerator == Accelerator.GPU
            }
            // Try the GPU (Vulkan/Metal) server; fall back to CPU if it can't start (no driver),
            // unless GPU was explicitly forced.
            val started = try {
                startServer(wantGpu, modelPath, config, mmproj)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                if (wantGpu && forced != Variant.GPU) {
                    logger("GPU server failed to start (${t.message}); falling back to CPU")
                    startServer(false, modelPath, config, mmproj)
                } else {
                    throw t
                }
            }
            val client = HttpClient(CIO) {
                expectSuccess = false
                install(HttpTimeout) {
                    // Streaming turns can run long on CPU; cap connect, leave request open.
                    connectTimeoutMillis = 30_000
                    requestTimeoutMillis = null
                    socketTimeoutMillis = null
                }
            }
            LlamaServerHandle(
                process = started.process,
                baseUrl = started.baseUrl,
                apiKey = started.process.apiKey,
                client = client,
                modelId = modelPath.substringAfterLast('/'),
                loadedAtEpochMs = System.currentTimeMillis(),
                activeAccelerator = if (started.gpuLayers > 0) Accelerator.GPU else Accelerator.CPU,
                visionEnabled = mmproj != null,
            )
        }

    /** Ensure the binary for the variant, spawn the server, await `/health`. */
    private suspend fun startServer(
        wantGpu: Boolean,
        modelPath: String,
        config: InferenceConfig,
        mmproj: String?,
    ): StartedServer {
        val binary = binaryStore.ensure(wantGpu)
        val gpuLayers = if (wantGpu) GPU_ALL_LAYERS else 0
        // Pin the GPU device only on the GPU variant; the CPU fallback ignores it (#78).
        val vulkanDevice = if (wantGpu) devicePinProvider() else null
        val process = LlamaServerProcess(
            binary = binary,
            modelPath = modelPath,
            mmprojPath = mmproj,
            ctxTokens = config.kvCacheTokens,
            gpuLayers = gpuLayers,
            vulkanDevice = vulkanDevice,
            logger = logger,
        )
        val baseUrl = process.start()
        return StartedServer(process, baseUrl, gpuLayers)
    }

    private class StartedServer(val process: LlamaServerProcess, val baseUrl: String, val gpuLayers: Int)

    private enum class Variant { CPU, GPU }

    /** `MOBILEAGENT_LLAMA_SERVER_VARIANT` = cpu | vulkan/gpu | auto(default→null). */
    private fun forcedVariant(): Variant? = when (System.getenv(ENV_VARIANT)?.trim()?.lowercase()) {
        "cpu" -> Variant.CPU
        "vulkan", "gpu", "metal" -> Variant.GPU
        else -> null
    }

    override fun unload(handle: ModelHandle) {
        (handle as? LlamaServerHandle)?.let {
            runCatching { it.client.close() }
            it.process.stop()
        }
    }

    override fun generate(
        handle: ModelHandle,
        request: GenerationRequest,
        toolDispatcher: ToolDispatcher?,
    ): Flow<GenerationEvent> = flow {
        val h = handle as LlamaServerHandle
        val temperature = request.sampling?.temperature ?: defaultTemperature
        val body = buildChatRequest(request, temperature)
        val hasImage = request.history.lastOrNull { it.role == HistoryRole.USER }?.imageBytes != null
        logger(
            "[generate] turn: historyTurns=${request.history.size} hasImage=$hasImage " +
                "maxTokens=${request.maxTokens} temp=$temperature sampling=${if (request.sampling != null) "greedy" else "default"}",
        )
        // Reasoning GGUFs may stream a thought channel inline; strip it (no-op otherwise).
        val stripper = ThinkingStripper()
        var generated = 0
        var chunkIndex = 0
        var finish = FinishReason.END_OF_TURN
        h.client.preparePost("${h.baseUrl}/v1/chat/completions") {
            headers { append(HttpHeaders.Authorization, "Bearer ${h.apiKey}") }
            contentType(ContentType.Application.Json)
            setBody(body)
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val err = runCatching { response.bodyAsText() }.getOrDefault("")
                emit(GenerationEvent.Error("llama-server HTTP ${response.status.value}: ${err.take(300)}"))
                return@execute
            }
            val channel = response.bodyAsChannel()
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = channel.readUTF8Line() ?: break
                if (line.isBlank() || !line.startsWith("data:")) continue
                val data = line.substringAfter("data:").trim()
                if (data == "[DONE]") break
                val (delta, fr) = parseStreamChunk(data)
                if (fr != null) finish = fr
                if (delta.isNotEmpty()) {
                    generated++
                    val visible = stripper.push(delta)
                    if (visible.isNotEmpty()) emit(GenerationEvent.TokenChunk(visible, chunkIndex++))
                }
            }
        }
        val tail = stripper.finish()
        if (tail.isNotEmpty()) emit(GenerationEvent.TokenChunk(tail, chunkIndex++))
        emit(GenerationEvent.Done(totalTokens = generated, finishReason = finish))
    }.catch { t ->
        // Emit our OWN errors (can't reach llama-server, parse failure). A
        // downstream collector failure — e.g. the desktop-link SSE client hanging
        // up mid-stream (Broken pipe) — is rethrown transparently by `catch`, never
        // re-emitted: emitting from a `catch {}` block inside `flow {}` would
        // violate flow exception transparency. CancellationException is likewise
        // rethrown, so the cancelled-collector path is unchanged.
        emit(GenerationEvent.Error(t.message ?: "llama-server generation failed", t))
    }.flowOn(ioDispatcher)

    private companion object {
        const val GPU_ALL_LAYERS = 999
        const val ENV_VARIANT = "MOBILEAGENT_LLAMA_SERVER_VARIANT"
    }
}

private val SERVER_JSON = Json { ignoreUnknownKeys = true }

/**
 * Build the `POST /v1/chat/completions` body: system instruction + history as messages, the
 * trailing USER turn carrying any image as a multipart `content` array (image FIRST, then
 * text — matching Android's `Contents.of(ImageBytes, Text)` and Gemma's trained convention).
 * `internal` + pure so the request shape is unit-testable without a running server.
 */
internal fun buildChatRequest(request: GenerationRequest, temperature: Float): String {
    val messages = buildJsonArray {
        request.systemInstruction?.takeIf { it.isNotBlank() }?.let { sys ->
            add(buildJsonObject { put("role", "system"); put("content", sys) })
        }
        val history = request.history
        history.forEachIndexed { i, m ->
            val isTrailingUser = i == history.lastIndex && m.role == HistoryRole.USER
            val image = if (isTrailingUser) m.imageBytes else null
            if (image != null) {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(
                                buildJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        put("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(image))
                                    }
                                },
                            )
                            if (m.text.isNotBlank()) {
                                add(buildJsonObject { put("type", "text"); put("text", m.text) })
                            }
                        }
                    },
                )
            } else {
                add(buildJsonObject { put("role", m.role.toServerRole()); put("content", m.text) })
            }
        }
    }
    return buildJsonObject {
        put("messages", messages)
        put("max_tokens", request.maxTokens)
        put("temperature", temperature)
        request.sampling?.let {
            put("top_k", it.topK)
            put("top_p", it.topP)
        }
        if (request.stopSequences.isNotEmpty()) {
            putJsonArray("stop") { request.stopSequences.forEach { add(it) } }
        }
        put("cache_prompt", true)
        put("stream", true)
    }.toString()
}

/** Parse one SSE `data:` JSON chunk → (content delta, finish reason or null). */
internal fun parseStreamChunk(data: String): Pair<String, FinishReason?> = runCatching {
    val choice = SERVER_JSON.parseToJsonElement(data).jsonObject["choices"]
        ?.jsonArray?.firstOrNull()?.jsonObject ?: return "" to null
    val delta = choice["delta"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
    val finish = when (choice["finish_reason"]?.jsonPrimitive?.contentOrNull) {
        null -> null
        "length" -> FinishReason.MAX_TOKENS
        "stop" -> FinishReason.END_OF_TURN
        else -> FinishReason.END_OF_TURN
    }
    delta to finish
}.getOrDefault("" to null)

private fun HistoryRole.toServerRole(): String = when (this) {
    HistoryRole.SYSTEM -> "system"
    HistoryRole.USER -> "user"
    HistoryRole.MODEL -> "assistant"
    HistoryRole.TOOL -> "user" // Gemma has no tool role; surface as a user turn.
}

private class LlamaServerHandle(
    val process: LlamaServerProcess,
    val baseUrl: String,
    val apiKey: String,
    val client: HttpClient,
    override val modelId: String,
    override val loadedAtEpochMs: Long,
    override val activeAccelerator: Accelerator,
    val visionEnabled: Boolean,
) : ModelHandle
