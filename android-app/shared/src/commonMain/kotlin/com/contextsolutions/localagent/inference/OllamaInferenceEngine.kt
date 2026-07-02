package com.contextsolutions.localagent.inference
import com.contextsolutions.localagent.platform.platformIoDispatcher

import com.contextsolutions.localagent.preferences.OllamaConfig
import com.contextsolutions.localagent.preferences.OllamaPreferences
import com.contextsolutions.localagent.preferences.RemoteServerType
import com.contextsolutions.localagent.platform.HttpEngineFactory
import com.contextsolutions.localagent.platform.SecureStorage
import com.contextsolutions.localagent.platform.SecureStorageKeys
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.datetime.Clock
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
 * Shared (commonMain) [InferenceEngine] that runs the large chat LLM on a remote
 * [Ollama](https://ollama.com) server over HTTP (PR #56). Used on BOTH platforms
 * via [RoutingInferenceEngine] — it's pure Ktor, so it needs no platform code.
 *
 * Generation uses Ollama's **OpenAI-compatible** `POST /v1/chat/completions`
 * (SSE streaming), which mirrors the desktop `LlamaServerInferenceEngine` almost
 * exactly: the trailing USER turn carries any image as a multipart `content`
 * array with an `image_url` data-URI (image FIRST, then text — invariant #39 is
 * preserved by [PromptAssembler] stripping bytes from non-trailing turns). Model
 * discovery lives in [OllamaClient] (`/api/tags`).
 *
 * Unlike the local engines there is no model to load into our process —
 * [loadModel] only health-probes the server (so [RoutingInferenceEngine] can
 * fall back to the on-device model when it's unreachable) and the model name
 * rides on every request. Speed: Ollama keeps the model resident server-side
 * (default 5-min keep-alive; we also pass `keep_alive` to extend it), so 2nd+
 * turns in a session skip the reload.
 *
 * NOTE: think-channel stripping (reasoning GGUFs) is not applied here — pick a
 * non-reasoning chat model, or follow up to port `ThinkingStripper` to common.
 */
class OllamaInferenceEngine(
    private val httpEngineFactory: HttpEngineFactory,
    private val preferences: OllamaPreferences,
    private val client: OllamaClient,
    /** Optional — drives offline/recovery handling (PR #56). See [OllamaConnectionMonitor]. */
    private val monitor: OllamaConnectionMonitor? = null,
    /**
     * Optional — supplies the user's [SecureStorageKeys.OLLAMA_API_KEY] (PR #58).
     * Read per-request (not baked into the handle) so a key change applies on the
     * next turn without a [RoutingInferenceEngine] reload; null/blank ⇒ no auth
     * header (the pre-#58 default).
     */
    private val secureStorage: SecureStorage? = null,
    private val defaultTemperature: Float = InferenceConfig().temperature,
    private val keepAlive: String = DEFAULT_KEEP_ALIVE,
    private val logger: (String) -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = platformIoDispatcher,
) : InferenceEngine {

    /**
     * Reads the configured server from [OllamaPreferences], health-probes it, and
     * returns a handle carrying the base URL + selected models. Throws when the
     * server is unconfigured or unreachable — [RoutingInferenceEngine] catches
     * that and falls back to the on-device engine. The [modelPath]/[config] args
     * from the seam are ignored (no local weights).
     */
    override suspend fun loadModel(modelPath: String, config: InferenceConfig): ModelHandle =
        withContext(ioDispatcher) {
            val cfg = preferences.config()
            val baseUrl = cfg.baseUrl()
                ?: error("Ollama server not configured")
            require(cfg.chatModel.isNotBlank()) { "Ollama chat model not selected" }
            if (!client.health(baseUrl, cfg.serverType)) {
                // The router will fall back to local; start watching so we
                // reconnect automatically once the server returns.
                monitor?.beginReconnectWatch(baseUrl)
                error("Ollama server unreachable at $baseUrl")
            }
            monitor?.onRemoteHealthy()
            // Streaming turns run long; build a dedicated un-timed client (the
            // factory's default 10s request timeout would abort mid-stream).
            val streamClient = httpEngineFactory.create {
                install(HttpTimeout) {
                    connectTimeoutMillis = 30_000
                    requestTimeoutMillis = null
                    socketTimeoutMillis = null
                }
            }
            logger("loaded: baseUrl=$baseUrl chat=${cfg.chatModel} vision=${cfg.visionModel.ifBlank { "(chat)" }}")
            OllamaHandle(
                baseUrl = baseUrl,
                config = cfg,
                client = streamClient,
                modelId = cfg.chatModel,
                loadedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
            )
        }

    override fun unload(handle: ModelHandle) {
        (handle as? OllamaHandle)?.let { runCatching { it.client.close() } }
    }

    override fun generate(
        handle: ModelHandle,
        request: GenerationRequest,
        toolDispatcher: ToolDispatcher?,
    ): Flow<GenerationEvent> {
        val h = handle as OllamaHandle
        // Captured for the `.catch` operator below — the flow lambda's locals are
        // not in scope there.
        val baseUrl = h.baseUrl
        return flow {
            val hasImage = request.history.lastOrNull { it.role == HistoryRole.USER }?.imageBytes != null
            val model = h.config.modelFor(hasImage)
            val temperature = request.sampling?.temperature ?: defaultTemperature
            val body = buildOllamaChatRequest(request, model, temperature, keepAlive, h.config.serverType)
            logger(
                "[generate] model=$model historyTurns=${request.history.size} hasImage=$hasImage " +
                    "maxTokens=${request.maxTokens} sampling=${if (request.sampling != null) "greedy" else "default"}",
            )
            var generated = 0
            var chunkIndex = 0
            var finish = FinishReason.END_OF_TURN
            var sawDataLine = false
            // Diagnostic (PR — remote-LLM mid-stream truncation): did the server tell
            // us it was done, or did the socket just end? A `finish_reason`/`[DONE]`
            // means the model stopped; a null-line EOF with `sawFinish=false` means the
            // transport dropped the stream early (the OkHttp-only clip we're chasing).
            var sawFinish = false
            var streamExit = "eof(null-line)"
            val url = "${h.baseUrl}${h.config.serverType.chatCompletionsPath}"
            // L3 — never send the API key over cleartext HTTP; refuse and let the
            // router fall back to the on-device model. Nudges the user to enable SSL.
            if (apiKey() != null && isCleartext(url)) {
                emit(
                    GenerationEvent.Error(
                        "Refusing to send the API key over cleartext HTTP. " +
                            "Enable SSL/HTTPS for this remote server.",
                    ),
                )
                return@flow
            }
            // PR #73 — outbound request diagnostic (URL incl. port, the JSON body/query,
            // and the *presence/absence* of the Bearer token). The API key itself is
            // never logged (L1) — only `Bearer <redacted>` / `(none)`.
            logger(
                "[request] POST $url | header Authorization: ${authLogValue()} | body=$body",
            )
            h.client.preparePost(url) {
                contentType(ContentType.Application.Json)
                apiKey()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                setBody(body)
            }.execute { response ->
                // We reached the server (even an HTTP error means it's up) — clear
                // any pending reconnect watch.
                monitor?.onRemoteHealthy()
                if (!response.status.isSuccess()) {
                    val err = runCatching { response.bodyAsText() }.getOrDefault("")
                    emit(GenerationEvent.Error("Remote LLM HTTP ${response.status.value}: ${err.take(300)}"))
                    return@execute
                }
                // Transport-level details that differ per HTTP engine (OkHttp vs CIO)
                // and can silently truncate a long SSE stream: HTTP/2 vs 1.1, a gzip
                // Content-Encoding on a stream, or a fixed Content-Length capping a
                // chunked body. Logged once per turn to compare mobile-clip vs desktop-ok.
                logger(
                    "[response] proto=${response.version} " +
                        "contentLength=${response.headers[HttpHeaders.ContentLength] ?: "(none)"} " +
                        "transferEncoding=${response.headers[HttpHeaders.TransferEncoding] ?: "(none)"} " +
                        "contentEncoding=${response.headers[HttpHeaders.ContentEncoding] ?: "(none)"} " +
                        "contentType=${response.headers[HttpHeaders.ContentType] ?: "(none)"}",
                )
                val channel = response.bodyAsChannel()
                var lineCount = 0
                var charCount = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = channel.readUTF8Line()
                    if (line == null) {
                        streamExit = "eof(null-line)"
                        break
                    }
                    lineCount++
                    if (line.isBlank() || !line.startsWith("data:")) continue
                    sawDataLine = true
                    val data = line.substringAfter("data:").trim()
                    if (data == "[DONE]") {
                        streamExit = "done-marker"
                        break
                    }
                    val (delta, fr) = parseOllamaStreamChunk(data)
                    if (fr != null) {
                        finish = fr
                        sawFinish = true
                        streamExit = "finish=$fr"
                    }
                    if (delta.isNotEmpty()) {
                        generated++
                        charCount += delta.length
                        emit(GenerationEvent.TokenChunk(delta, chunkIndex++))
                    }
                }
                logger(
                    "[stream-end] exit=$streamExit sawFinish=$sawFinish finish=$finish " +
                        "lines=$lineCount chunks=$generated chars=$charCount",
                )
            }
            if (generated == 0 && !sawDataLine) {
                // 200 but not an SSE stream (e.g. an HTML page from a wrong Base
                // URL path, or a non-streaming endpoint). An empty bubble hides
                // this — surface it as an actionable error instead.
                emit(
                    GenerationEvent.Error(
                        "Remote LLM returned no streamed content (HTTP 200 but no SSE data). " +
                            "Check the Base URL path and the selected model.",
                    ),
                )
            } else {
                emit(GenerationEvent.Done(totalTokens = generated, finishReason = finish))
            }
        }.catch { t ->
            // Couldn't reach the server (refused, timeout, dropped mid-stream): fall
            // back to local + watch for its return (PR #56). HTTP-status / no-SSE
            // errors are emitted in-flow above (the server is up), so they don't
            // reach here. A downstream collector failure — e.g. the desktop-link SSE
            // client hanging up (Broken pipe) — is rethrown transparently by `catch`
            // and is NEVER re-emitted: emitting from `catch {}` would violate flow
            // exception transparency, and such a failure must not mark the server
            // unreachable (it's the consumer that left). CancellationException is
            // likewise rethrown by `catch`, so the cancelled-collector path is unchanged.
            logger("[catch] ${t::class.simpleName}: ${t.message}")
            monitor?.onRemoteUnreachable(baseUrl)
            emit(GenerationEvent.Error(t.message ?: "Ollama generation failed", t))
        }.flowOn(ioDispatcher)
    }

    /** The configured outbound API key, or null when unset/blank (PR #58). */
    private fun apiKey(): String? =
        secureStorage?.get(SecureStorageKeys.OLLAMA_API_KEY)?.takeIf { it.isNotBlank() }

    /** Redacted Authorization value for logs (L1) — never the key itself. */
    private fun authLogValue(): String = if (apiKey() != null) "Bearer <redacted>" else "(none)"

    private companion object {
        const val DEFAULT_KEEP_ALIVE = "30m"
    }
}

private val OLLAMA_JSON = Json { ignoreUnknownKeys = true }

/**
 * Build the OpenAI-compatible `POST /v1/chat/completions` body for Ollama:
 * `model` + system instruction + history as messages, the trailing USER turn
 * carrying any image as a multipart `content` array (image FIRST, then text).
 * `internal` + pure so the request shape is unit-testable without a server.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun buildOllamaChatRequest(
    request: GenerationRequest,
    model: String,
    temperature: Float,
    keepAlive: String,
    serverType: RemoteServerType = RemoteServerType.OLLAMA,
): String {
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
                                        put("url", "data:image/jpeg;base64," + Base64.Default.encode(image))
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
                add(buildJsonObject { put("role", m.role.toOllamaRole()); put("content", m.text) })
            }
        }
    }
    return buildJsonObject {
        put("model", model)
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
        // Keep the model resident on the Ollama side between turns (speed). Ollama
        // reads keep_alive from the OpenAI-compatible body; a generic OpenAI server
        // may reject the unknown field, so send it only for the Ollama backend.
        if (serverType == RemoteServerType.OLLAMA) {
            put("keep_alive", keepAlive)
        }
        put("stream", true)
    }.toString()
}

/** Parse one SSE `data:` JSON chunk → (content delta, finish reason or null). */
internal fun parseOllamaStreamChunk(data: String): Pair<String, FinishReason?> = runCatching {
    val choice = OLLAMA_JSON.parseToJsonElement(data).jsonObject["choices"]
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

private fun HistoryRole.toOllamaRole(): String = when (this) {
    HistoryRole.SYSTEM -> "system"
    HistoryRole.USER -> "user"
    HistoryRole.MODEL -> "assistant"
    HistoryRole.TOOL -> "user" // Gemma-style models have no tool role; surface as a user turn.
}

private class OllamaHandle(
    val baseUrl: String,
    val config: OllamaConfig,
    val client: HttpClient,
    override val modelId: String,
    override val loadedAtEpochMs: Long,
) : ModelHandle {
    // Remote inference: the accelerator is the server's concern, not ours. REMOTE
    // tells the UI to skip the local-load banner ("Loaded on CPU/GPU…").
    override val activeAccelerator: Accelerator = Accelerator.REMOTE
}
