package com.contextsolutions.mobileagent.inference

import com.contextsolutions.mobileagent.preferences.DesktopLinkPreferences
import com.contextsolutions.mobileagent.preferences.OllamaPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * [InferenceEngine] that routes the large chat LLM to a remote backend when the
 * user has configured one, otherwise to the on-device engine ([local], LiteRT-LM
 * on Android / llama-server on desktop). PR #56 added the remote Ollama backend;
 * PR #57 adds a paired-desktop backend that takes **priority** over Ollama.
 *
 * This sits at the [InferenceEngine] seam — below the Android
 * `InferenceSessionManager` and the desktop warm-model runtime — so BOTH
 * platforms get remote routing without touching their lifecycle/session layers
 * or the agent loop. It becomes the value of `single<InferenceEngine>` on each
 * platform, wrapping that platform's existing local engine.
 *
 * The backend is decided once per [loadModel], for the whole resident period, in
 * priority order:
 *  1. **Desktop link** configured ([DesktopLinkPreferences], toggled on + paired)
 *     **and reachable** → the [desktopLink] engine. (PR #57.) When the desktop
 *     link is active the Ollama server is ignored entirely.
 *  2. else **Ollama** configured ([OllamaPreferences]) **and reachable** → [ollama].
 *  3. else / any remote unreachable → fall back to [local] (chat keeps working
 *     on-device rather than erroring).
 *
 * Toggling the link / editing the server in Settings must drop a resident model
 * so the next turn re-decides — the Android session manager / desktop runtime
 * observe both [DesktopLinkPreferences.configFlow] and [OllamaPreferences.configFlow]
 * (plus the connection monitors) and force an unload (see PR #56/#57 wiring).
 */
class RoutingInferenceEngine(
    private val local: InferenceEngine,
    /** Remote engines — typed as the seam so the fallback matrix is testable with fakes. */
    private val ollama: InferenceEngine,
    private val preferences: OllamaPreferences,
    /** Paired-desktop engine (PR #57); null disables the desktop-link path entirely. */
    private val desktopLink: InferenceEngine? = null,
    private val desktopLinkPreferences: DesktopLinkPreferences? = null,
    private val logger: (String) -> Unit = {},
) : InferenceEngine {

    private enum class Backend { LOCAL, OLLAMA, DESKTOP_LINK }

    private class RoutedHandle(
        val backend: Backend,
        val delegate: ModelHandle,
    ) : ModelHandle by delegate

    override suspend fun loadModel(modelPath: String, config: InferenceConfig): ModelHandle {
        // 1. Paired desktop link wins when enabled + reachable (PR #57).
        if (desktopLink != null && desktopLinkPreferences?.config()?.isLinkConfigured == true) {
            try {
                val handle = desktopLink.loadModel(modelPath, config)
                logger("routing → desktop link (${handle.modelId})")
                return RoutedHandle(Backend.DESKTOP_LINK, handle)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                logger("Desktop link unavailable (${t.message}); trying next backend")
            }
        }
        // 2. Directly-configured Ollama server (PR #56).
        if (preferences.config().isConfigured) {
            try {
                val handle = ollama.loadModel(modelPath, config)
                logger("routing → Ollama (${handle.modelId})")
                return RoutedHandle(Backend.OLLAMA, handle)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                logger("Ollama unavailable (${t.message}); falling back to on-device model")
            }
        }
        // 3. On-device.
        return RoutedHandle(Backend.LOCAL, local.loadModel(modelPath, config))
    }

    override fun unload(handle: ModelHandle) {
        val routed = handle as? RoutedHandle ?: return local.unload(handle)
        when (routed.backend) {
            Backend.LOCAL -> local.unload(routed.delegate)
            Backend.OLLAMA -> ollama.unload(routed.delegate)
            Backend.DESKTOP_LINK -> desktopLink?.unload(routed.delegate)
        }
    }

    override fun generate(
        handle: ModelHandle,
        request: GenerationRequest,
        toolDispatcher: ToolDispatcher?,
    ): Flow<GenerationEvent> {
        val routed = handle as? RoutedHandle
            ?: return local.generate(handle, request, toolDispatcher)
        return when (routed.backend) {
            Backend.LOCAL -> local.generate(routed.delegate, request, toolDispatcher)
            Backend.OLLAMA -> ollama.generate(routed.delegate, request, toolDispatcher)
            Backend.DESKTOP_LINK ->
                desktopLink?.generate(routed.delegate, request, toolDispatcher)
                    ?: local.generate(routed.delegate, request, toolDispatcher)
        }
    }
}
