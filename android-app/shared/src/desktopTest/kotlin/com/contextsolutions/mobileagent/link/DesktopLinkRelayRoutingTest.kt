package com.contextsolutions.mobileagent.link

import com.contextsolutions.mobileagent.agent.InferenceSession
import com.contextsolutions.mobileagent.inference.Accelerator
import com.contextsolutions.mobileagent.inference.FinishReason
import com.contextsolutions.mobileagent.inference.GenerationEvent
import com.contextsolutions.mobileagent.inference.GenerationRequest
import com.contextsolutions.mobileagent.inference.InferenceConfig
import com.contextsolutions.mobileagent.inference.InferenceEngine
import com.contextsolutions.mobileagent.inference.ModelHandle
import com.contextsolutions.mobileagent.inference.RoutingInferenceEngine
import com.contextsolutions.mobileagent.inference.ToolDispatcher
import com.contextsolutions.mobileagent.link.transport.LinkMethod
import com.contextsolutions.mobileagent.link.transport.LinkRequest
import com.contextsolutions.mobileagent.link.transport.LinkStreamEvent
import com.contextsolutions.mobileagent.preferences.DesktopLinkConfig
import com.contextsolutions.mobileagent.preferences.DesktopLinkPreferences
import com.contextsolutions.mobileagent.preferences.OllamaConfig
import com.contextsolutions.mobileagent.preferences.OllamaPreferences
import com.contextsolutions.mobileagent.sync.LinkSyncService
import com.contextsolutions.mobileagent.sync.SyncBundle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * PR #80 — proves the relay path serves a mobile chat from the desktop's REMOTE LLM
 * when one is configured. A relayed CHAT request reaches [DesktopLinkRequestHandler],
 * which drives the desktop's warm-model session; in production that session is backed
 * by the [RoutingInferenceEngine] singleton (DesktopModule), so a configured + active
 * Ollama/OpenAI server serves the turn instead of the local llama-server. This test
 * wires the handler's session to exactly that engine and asserts the streamed token
 * came from the remote backend (and falls back to local when no remote is configured).
 */
class DesktopLinkRelayRoutingTest {

    private val chatBody = """{"messages":[{"role":"user","content":"hi"}]}"""

    @Test
    fun relayChatUsesRemoteLlmWhenDesktopHasOneConfigured() = runBlocking {
        val served = serveRelayChat(remote = OllamaConfig(host = "1.2.3.4", port = 11434, chatModel = "m"))
        assertTrue("ollama" in served, "expected the remote backend to serve, got: $served")
    }

    @Test
    fun relayChatFallsBackToLocalWhenNoRemoteConfigured() = runBlocking {
        val served = serveRelayChat(remote = OllamaConfig.EMPTY)
        assertTrue("local" in served, "expected the local backend to serve, got: $served")
    }

    /** Build the production wiring (handler → routing engine) and return the streamed text. */
    private suspend fun serveRelayChat(remote: OllamaConfig): String {
        val routing = RoutingInferenceEngine(
            local = FakeEngine("local"),
            ollama = FakeEngine("ollama"),
            preferences = FakeOllamaPrefs(remote),
        )
        // Mirror WarmModel: a session that loads once then streams from the routed engine.
        val session = object : InferenceSession {
            override fun generate(request: GenerationRequest, toolDispatcher: ToolDispatcher?): Flow<GenerationEvent> = flow {
                val handle = routing.loadModel("p", InferenceConfig())
                emitAll(routing.generate(handle, request, toolDispatcher))
            }
        }
        val handler = DesktopLinkRequestHandler(
            preferences = FakeLinkPrefs(),
            sessionProvider = { session },
            syncService = NoOpSyncService(),
        )
        val events = handler.handleStream(LinkRequest(LinkMethod.CHAT, body = chatBody)).toList()
        return events.filterIsInstance<LinkStreamEvent.Data>().joinToString(" ") { it.body }
    }

    private class FakeHandle(override val modelId: String) : ModelHandle {
        override val loadedAtEpochMs = 0L
        override val activeAccelerator = Accelerator.CPU
    }

    private class FakeEngine(val name: String) : InferenceEngine {
        override suspend fun loadModel(modelPath: String, config: InferenceConfig): ModelHandle = FakeHandle(name)
        override fun unload(handle: ModelHandle) {}
        override fun generate(
            handle: ModelHandle,
            request: GenerationRequest,
            toolDispatcher: ToolDispatcher?,
        ): Flow<GenerationEvent> = flowOf(
            GenerationEvent.TokenChunk(name, 0),
            GenerationEvent.Done(1, FinishReason.END_OF_TURN),
        )
    }

    private class FakeOllamaPrefs(initial: OllamaConfig) : OllamaPreferences {
        private val s = MutableStateFlow(initial)
        override fun config() = s.value
        override fun configFlow() = s
        override fun setConfig(config: OllamaConfig) { s.value = config }
    }

    private class FakeLinkPrefs : DesktopLinkPreferences {
        private val s = MutableStateFlow(DesktopLinkConfig(selfDeviceId = "dev-x"))
        override fun config() = s.value
        override fun configFlow() = s
        override fun setConfig(config: DesktopLinkConfig) { s.value = config }
    }

    private class NoOpSyncService : LinkSyncService {
        override suspend fun changesSince(sinceMs: Long): SyncBundle = SyncBundle()
        override suspend fun applyFromPeer(bundle: SyncBundle) {}
        override val localChanges: SharedFlow<Unit> = MutableSharedFlow()
    }
}
