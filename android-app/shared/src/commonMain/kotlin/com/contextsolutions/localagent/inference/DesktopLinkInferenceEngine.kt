package com.contextsolutions.localagent.inference

import com.contextsolutions.localagent.link.transport.LinkMethod
import com.contextsolutions.localagent.link.transport.LinkRequest
import com.contextsolutions.localagent.link.transport.LinkStreamEvent
import com.contextsolutions.localagent.link.transport.LinkTransport
import com.contextsolutions.localagent.link.transport.LinkTransportProvider
import kotlinx.datetime.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Shared (commonMain) [InferenceEngine] that routes the large chat LLM to a paired
 * **desktop agent** (PR #57). The phone scans a QR on the desktop's Settings page
 * ([com.contextsolutions.localagent.preferences.DesktopLinkPreferences]); when the
 * link is enabled + reachable, [RoutingInferenceEngine] picks this engine — ahead
 * of a directly-configured Ollama server.
 *
 * It is transport-agnostic: it issues [LinkRequest]s through whatever
 * [LinkTransportProvider] selects — the LAN HTTP path (PR #57) or, when a relay
 * subscription is active, the E2EE relay (the follow-up). Either way the desktop
 * proxy serves the same **OpenAI-compatible** chat surface, so we reuse
 * [buildOllamaChatRequest] + [parseOllamaStreamChunk] verbatim. The model name is
 * irrelevant — the desktop serves from *its own* warm model — so we send a
 * constant placeholder.
 *
 * Reachability + offline/recovery is handled via an [OllamaConnectionMonitor]
 * instance (the desktop-link one), so a dropped link falls back to the on-device
 * model and reconnects automatically.
 */
class DesktopLinkInferenceEngine(
    private val transports: LinkTransportProvider,
    /** The desktop-link [OllamaConnectionMonitor] (reused, generic over health). */
    private val monitor: OllamaConnectionMonitor? = null,
    private val defaultTemperature: Float = InferenceConfig().temperature,
    private val keepAlive: String = "30m",
    private val logger: (String) -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : InferenceEngine {

    override suspend fun loadModel(modelPath: String, config: InferenceConfig): ModelHandle =
        withContext(ioDispatcher) {
            val transport = transports.current() ?: error("Desktop link not configured")
            val health = transport.unary(LinkRequest(LinkMethod.HEALTH))
            if (!health.isSuccess) {
                // The router will fall back to local; start watching so we
                // reconnect automatically once the desktop returns.
                monitor?.beginReconnectWatch(transport.target)
                error("Desktop link unreachable at ${transport.target}")
            }
            monitor?.onRemoteHealthy()
            logger("loaded: target=${transport.target}")
            DesktopLinkHandle(
                transport = transport,
                loadedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
            )
        }

    override fun unload(handle: ModelHandle) {
        // The transport is owned by the provider and shared across loads — nothing to close.
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
        h.transport.serverStream(LinkRequest(LinkMethod.CHAT, body = body)).collect { event ->
            when (event) {
                is LinkStreamEvent.Data -> {
                    val (delta, fr) = parseOllamaStreamChunk(event.body)
                    if (fr != null) finish = fr
                    if (delta.isNotEmpty()) {
                        generated++
                        emit(GenerationEvent.TokenChunk(delta, chunkIndex++))
                    }
                }
                is LinkStreamEvent.End -> emit(GenerationEvent.Done(totalTokens = generated, finishReason = finish))
                is LinkStreamEvent.Error -> {
                    // status 0 ⇒ couldn't reach the desktop (refused/timeout/dropped):
                    // fall back to local now + watch for its return. HTTP-status errors
                    // mean the desktop is up, so they're a plain error (no watch).
                    if (event.status == 0) monitor?.onRemoteUnreachable(h.transport.target)
                    emit(GenerationEvent.Error("Desktop link error ${event.status}: ${event.message}"))
                }
            }
        }
    }.flowOn(ioDispatcher)

    private companion object {
        const val PROXY_MODEL = "desktop"
    }
}

private class DesktopLinkHandle(
    val transport: LinkTransport,
    override val loadedAtEpochMs: Long,
) : ModelHandle {
    override val modelId: String = "desktop-link"
    // Remote inference: the accelerator is the desktop's concern. REMOTE tells the
    // UI to skip the local-load banner ("Loaded on CPU/GPU…").
    override val activeAccelerator: Accelerator = Accelerator.REMOTE
}
