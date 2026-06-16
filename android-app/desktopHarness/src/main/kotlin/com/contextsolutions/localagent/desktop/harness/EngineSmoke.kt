package com.contextsolutions.localagent.desktop.harness

import com.contextsolutions.localagent.inference.GenerationEvent
import com.contextsolutions.localagent.inference.GenerationRequest
import com.contextsolutions.localagent.inference.HistoryMessage
import com.contextsolutions.localagent.inference.HistoryRole
import com.contextsolutions.localagent.inference.InferenceConfig
import com.contextsolutions.localagent.inference.LlamaServerBinaryStore
import com.contextsolutions.localagent.inference.LlamaServerInferenceEngine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

/**
 * Minimal smoke test for [LlamaServerInferenceEngine] (PR #55 Option 3), isolated
 * from AgentLoop: ensures the prebuilt llama-server, loads a GGUF, and streams a
 * short bounded chat completion so we can confirm the server starts + streams
 * tokens. Set GEMMA_GGUF_PATH; optionally LOCALAGENT_LLAMA_SERVER to skip the
 * binary download. Run via the installed distribution.
 */
fun main(): Unit = runBlocking {
    val modelPath = System.getenv("GEMMA_GGUF_PATH") ?: error("set GEMMA_GGUF_PATH")
    val engine = LlamaServerInferenceEngine(binaryStore = LlamaServerBinaryStore())
    System.err.println("[smoke] loading $modelPath")
    val handle = engine.loadModel(modelPath, InferenceConfig())
    System.err.println("[smoke] loaded on ${handle.activeAccelerator}; generating (cap 48)…")
    val req = GenerationRequest(
        history = listOf(HistoryMessage(HistoryRole.USER, "The capital of France is")),
        maxTokens = 48,
    )
    var n = 0
    engine.generate(handle, req).collect { ev ->
        when (ev) {
            is GenerationEvent.TokenChunk -> { print(ev.text); System.out.flush(); n++ }
            is GenerationEvent.Done -> System.err.println("\n[smoke] done: ${ev.totalTokens} tokens, ${ev.finishReason}")
            is GenerationEvent.Error -> System.err.println("\n[smoke] error: ${ev.message}")
            else -> {}
        }
    }
    engine.unload(handle)
    System.err.println("[smoke] OK ($n chunks)")
    kotlin.system.exitProcess(0)
}
