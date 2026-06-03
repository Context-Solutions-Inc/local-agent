package com.contextsolutions.mobileagent.inference

import com.contextsolutions.mobileagent.preferences.DesktopLinkConfig
import com.contextsolutions.mobileagent.preferences.DesktopLinkPreferences
import com.contextsolutions.mobileagent.platform.HttpEngineFactory
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
import kotlinx.datetime.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Shared (commonMain) [InferenceEngine] that routes the large chat LLM to a paired
 * **desktop agent** over the LAN (PR #57). The phone scans a QR on the desktop's
 * Settings page ([DesktopLinkPreferences]); when the link is enabled + reachable,
 * [RoutingInferenceEngine] picks this engine — ahead of a directly-configured
 * Ollama server.
 *
 * It is almost identical to [OllamaInferenceEngine]: the desktop link server
 * exposes the same **OpenAI-compatible** `POST /v1/chat/completions` (SSE), so we
 * reuse [buildOllamaChatRequest] + [parseOllamaStreamChunk] verbatim. Two
 * differences:
 *  - every request carries the QR pairing token as `Authorization: Bearer` (the
 *    desktop rejects un-paired LAN clients);
 *  - the model name is irrelevant — the desktop proxy serves from *its own* warm
 *    model (which may itself be the desktop's local LLM or the desktop's own
 *    remote Ollama; the phone never sees that downstream endpoint). We send a
 *    constant placeholder.
 *
 * Reachability + offline/recovery is handled exactly like Ollama via an
 * [OllamaConnectionMonitor] instance (the desktop-link one), so a dropped link
 * falls back to the on-device model and reconnects automatically.
 */
class DesktopLinkInferenceEngine(
    private val httpEngineFactory: HttpEngineFactory,
    private val preferences: DesktopLinkPreferences,
    private val client: DesktopLinkClient,
    /** The desktop-link [OllamaConnectionMonitor] (reused, generic over health). */
    private val monitor: OllamaConnectionMonitor? = null,
    private val defaultTemperature: Float = InferenceConfig().temperature,
    private val keepAlive: String = "30m",
    private val logger: (String) -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : InferenceEngine {

    override suspend fun loadModel(modelPath: String, config: InferenceConfig): ModelHandle =
        withContext(ioDispatcher) {
            val cfg = preferences.config()
            val baseUrl = cfg.baseUrl() ?: error("Desktop link not configured")
            require(cfg.pairingToken.isNotBlank()) { "Desktop link not paired" }
            if (!client.health(baseUrl, cfg.pairingToken)) {
                // The router will fall back to local; start watching so we
                // reconnect automatically once the desktop returns.
                monitor?.beginReconnectWatch(baseUrl)
                error("Desktop link unreachable at $baseUrl")
            }
            monitor?.onRemoteHealthy()
            val streamClient = httpEngineFactory.create {
                install(HttpTimeout) {
                    connectTimeoutMillis = 30_000
                    requestTimeoutMillis = null
                    socketTimeoutMillis = null
                }
            }
            logger("loaded: baseUrl=$baseUrl peer=${cfg.pairedDeviceId.take(12)}")
            DesktopLinkHandle(
                baseUrl = baseUrl,
                config = cfg,
                client = streamClient,
                loadedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
            )
        }

    override fun unload(handle: ModelHandle) {
        (handle as? DesktopLinkHandle)?.let { runCatching { it.client.close() } }
    }

    override fun generate(
        handle: ModelHandle,
        request: GenerationRequest,
        toolDispatcher: ToolDispatcher?,
    ): Flow<GenerationEvent> = flow {
        val h = handle as DesktopLinkHandle
        val hasImage = request.history.lastOrNull { it.role == HistoryRole.USER }?.imageBytes != null
        val temperature = request.sampling?.temperature ?: defaultTemperature
        // The desktop proxy ignores `model` (serves its own warm model); the name
        // is required by the OpenAI body shape, so send a stable placeholder.
        val body = buildOllamaChatRequest(request, PROXY_MODEL, temperature, keepAlive)
        logger(
            "[generate] historyTurns=${request.history.size} hasImage=$hasImage " +
                "maxTokens=${request.maxTokens} sampling=${if (request.sampling != null) "greedy" else "default"}",
        )
        var generated = 0
        var chunkIndex = 0
        var finish = FinishReason.END_OF_TURN
        try {
            h.client.preparePost("${h.baseUrl}/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer ${h.config.pairingToken}")
                contentType(ContentType.Application.Json)
                setBody(body)
            }.execute { response ->
                monitor?.onRemoteHealthy()
                if (!response.status.isSuccess()) {
                    val err = runCatching { response.bodyAsText() }.getOrDefault("")
                    emit(GenerationEvent.Error("Desktop link HTTP ${response.status.value}: ${err.take(300)}"))
                    return@execute
                }
                val channel = response.bodyAsChannel()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = channel.readUTF8Line() ?: break
                    if (line.isBlank() || !line.startsWith("data:")) continue
                    val data = line.substringAfter("data:").trim()
                    if (data == "[DONE]") break
                    val (delta, fr) = parseOllamaStreamChunk(data)
                    if (fr != null) finish = fr
                    if (delta.isNotEmpty()) {
                        generated++
                        emit(GenerationEvent.TokenChunk(delta, chunkIndex++))
                    }
                }
            }
            emit(GenerationEvent.Done(totalTokens = generated, finishReason = finish))
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Couldn't reach the desktop (refused, timeout, dropped mid-stream):
            // fall back to local now and watch for its return. HTTP-status errors
            // don't land here — the desktop is up, so they're a plain error.
            monitor?.onRemoteUnreachable(h.baseUrl)
            emit(GenerationEvent.Error(t.message ?: "Desktop link generation failed", t))
        }
    }.flowOn(ioDispatcher)

    private companion object {
        const val PROXY_MODEL = "desktop"
    }
}

private class DesktopLinkHandle(
    val baseUrl: String,
    val config: DesktopLinkConfig,
    val client: HttpClient,
    override val loadedAtEpochMs: Long,
) : ModelHandle {
    override val modelId: String = "desktop-link"
    // Remote inference: the accelerator is the desktop's concern. REMOTE tells the
    // UI to skip the local-load banner ("Loaded on CPU/GPU…").
    override val activeAccelerator: Accelerator = Accelerator.REMOTE
}
