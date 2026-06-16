package com.contextsolutions.localagent.inference

import com.contextsolutions.localagent.preferences.OllamaConfig
import com.contextsolutions.localagent.preferences.OllamaPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * PR #56 — the backend-selection matrix: unconfigured → local; configured +
 * reachable → Ollama; configured + unreachable → local fallback. Each fake
 * engine tags its stream with its name so we can see which backend served.
 */
class RoutingInferenceEngineTest {

    private val configured = OllamaConfig(host = "1.2.3.4", port = 11434, chatModel = "m")

    @Test
    fun unconfiguredRoutesToLocal() = runBlocking {
        val (local, ollama) = FakeEngine("local") to FakeEngine("ollama")
        val routing = RoutingInferenceEngine(local, ollama, FakePrefs(OllamaConfig.EMPTY))
        assertEquals("local", serve(routing))
        assertEquals(0, ollama.loadCount)
    }

    @Test
    fun configuredReachableRoutesToOllama() = runBlocking {
        val (local, ollama) = FakeEngine("local") to FakeEngine("ollama")
        val routing = RoutingInferenceEngine(local, ollama, FakePrefs(configured))
        assertEquals("ollama", serve(routing))
        assertEquals(0, local.loadCount)
    }

    @Test
    fun configuredUnreachableFallsBackToLocal() = runBlocking {
        val local = FakeEngine("local")
        val ollama = FakeEngine("ollama", failLoad = true)
        val routing = RoutingInferenceEngine(local, ollama, FakePrefs(configured))
        assertEquals("local", serve(routing))
        assertTrue(ollama.loadCount == 1, "Ollama load was attempted")
    }

    @Test
    fun unloadGoesToTheBackendThatLoaded() = runBlocking {
        val local = FakeEngine("local")
        val ollama = FakeEngine("ollama")
        val routing = RoutingInferenceEngine(local, ollama, FakePrefs(configured))
        val handle = routing.loadModel("p", InferenceConfig())
        routing.unload(handle)
        assertTrue(ollama.unloaded && !local.unloaded)
    }

    private suspend fun serve(routing: RoutingInferenceEngine): String {
        val handle = routing.loadModel("p", InferenceConfig())
        val events = routing.generate(handle, GenerationRequest()).toList()
        return (events.first() as GenerationEvent.TokenChunk).text
    }

    private class FakeHandle(override val modelId: String) : ModelHandle {
        override val loadedAtEpochMs = 0L
        override val activeAccelerator = Accelerator.CPU
    }

    private class FakeEngine(val name: String, val failLoad: Boolean = false) : InferenceEngine {
        var loadCount = 0
        var unloaded = false
        override suspend fun loadModel(modelPath: String, config: InferenceConfig): ModelHandle {
            loadCount++
            if (failLoad) error("$name unreachable")
            return FakeHandle(name)
        }
        override fun unload(handle: ModelHandle) { unloaded = true }
        override fun generate(
            handle: ModelHandle,
            request: GenerationRequest,
            toolDispatcher: ToolDispatcher?,
        ): Flow<GenerationEvent> = flowOf(
            GenerationEvent.TokenChunk(name, 0),
            GenerationEvent.Done(1, FinishReason.END_OF_TURN),
        )
    }

    private class FakePrefs(initial: OllamaConfig) : OllamaPreferences {
        private val s = MutableStateFlow(initial)
        override fun config() = s.value
        override fun configFlow() = s
        override fun setConfig(config: OllamaConfig) { s.value = config }
    }
}
