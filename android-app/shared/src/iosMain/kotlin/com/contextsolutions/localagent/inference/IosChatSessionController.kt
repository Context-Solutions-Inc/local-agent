package com.contextsolutions.localagent.inference

import com.contextsolutions.localagent.agent.ChatSessionController
import com.contextsolutions.localagent.agent.InferenceSession
import com.contextsolutions.localagent.preferences.DesktopLinkPreferences
import com.contextsolutions.localagent.preferences.OllamaPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * iOS [ChatSessionController] (PR #41) — wraps the [InferenceEngine] (a
 * `RoutingInferenceEngine` over [LiteRtIosInferenceEngine] + Ollama + the desktop link)
 * and keeps the model resident once loaded, like the desktop `DesktopChatSessionController`
 * (no Android foreground service). [state] transitions Unloaded → Loading → Loaded around
 * the first [newSession]; the accelerator comes from the loaded handle (GPU on Metal, CPU
 * fallback, REMOTE when routed to Ollama/desktop).
 *
 * **Re-decide on connectivity/settings change (#44).** `RoutingInferenceEngine` decides the
 * backend once per `loadModel` and tags the handle. So the resident handle is dropped when
 * (a) the remote-Ollama or desktop-link config changes (pair/unpair/toggle) and (b) either
 * connection monitor reports offline/online — mirroring Android's `LocalAgentApplication`
 * hooks. The next [newSession] then re-runs `loadModel` and re-decides desktop-link ↔ Ollama
 * ↔ on-device (e.g. falling back to the device GPU after the relay disconnects).
 */
class IosChatSessionController(
    private val engine: InferenceEngine,
    private val modelPath: () -> String,
    private val config: InferenceConfig = InferenceConfig(enableVision = true),
    ollamaMonitor: OllamaConnectionMonitor? = null,
    desktopLinkMonitor: OllamaConnectionMonitor? = null,
    ollamaPreferences: OllamaPreferences? = null,
    desktopLinkPreferences: DesktopLinkPreferences? = null,
    scope: CoroutineScope? = null,
) : ChatSessionController {

    private val _state = MutableStateFlow<SessionState>(SessionState.Unloaded)
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var handle: ModelHandle? = null

    init {
        // Drop the resident handle on any backend-affecting signal so the next turn re-decides.
        // configFlow: drop(1) skips the replayed current value at startup (nothing loaded yet);
        // reloadRequests are no-replay SharedFlows, so they never fire spuriously at startup.
        if (scope != null) {
            val signals: List<Flow<Unit>> = listOfNotNull(
                ollamaMonitor?.reloadRequests,
                desktopLinkMonitor?.reloadRequests,
                ollamaPreferences?.configFlow()?.drop(1)?.distinctUntilChanged()?.map { },
                desktopLinkPreferences?.configFlow()?.drop(1)?.distinctUntilChanged()?.map { },
            )
            if (signals.isNotEmpty()) {
                merge(*signals.toTypedArray())
                    .onEach { invalidate() }
                    .launchIn(scope)
            }
        }
    }

    override suspend fun newSession(): InferenceSession = mutex.withLock {
        val loaded = handle ?: load()
        IosInferenceSession(engine, loaded)
    }

    private suspend fun load(): ModelHandle {
        if (_state.value !is SessionState.Loaded) _state.value = SessionState.Loading
        return try {
            val h = engine.loadModel(modelPath(), config)
            handle = h
            _state.value = SessionState.Loaded(h.activeAccelerator)
            h
        } catch (t: Throwable) {
            _state.value = SessionState.Failed(t.message ?: "model load failed", t)
            throw t
        }
    }

    /**
     * Drop the resident model so the next [newSession] reloads and re-decides the backend.
     * An already-created [IosInferenceSession] keeps its own handle ref, so an in-flight turn
     * is unaffected; only the NEXT turn re-decides. Unload runs outside the lock.
     */
    private suspend fun invalidate() {
        val old: ModelHandle = mutex.withLock {
            val h = handle ?: return
            handle = null
            if (_state.value is SessionState.Loaded) _state.value = SessionState.Unloaded
            h
        }
        runCatching { engine.unload(old) }
    }
}

private class IosInferenceSession(
    private val engine: InferenceEngine,
    private val handle: ModelHandle,
) : InferenceSession {
    override fun generate(
        request: com.contextsolutions.localagent.inference.GenerationRequest,
        toolDispatcher: com.contextsolutions.localagent.inference.ToolDispatcher?,
    ): Flow<GenerationEvent> = engine.generate(handle, request, toolDispatcher)
}
