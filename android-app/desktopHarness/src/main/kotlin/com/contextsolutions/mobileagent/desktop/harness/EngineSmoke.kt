package com.contextsolutions.mobileagent.desktop.harness

import com.contextsolutions.mobileagent.inference.GenerationEvent
import com.contextsolutions.mobileagent.inference.GenerationRequest
import com.contextsolutions.mobileagent.inference.InferenceConfig
import com.contextsolutions.mobileagent.inference.LlamaCppInferenceEngine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

/**
 * Minimal native-path smoke test for [LlamaCppInferenceEngine], isolated from
 * AgentLoop: loads a GGUF and streams a short, bounded completion (raw prompt
 * path, 48-token cap) so we can confirm llama.cpp loads + streams tokens quickly
 * on CPU. Run via the installed distribution; see Phase 0 notes.
 */
fun main(): Unit = runBlocking {
    val modelPath = System.getenv("GEMMA_GGUF_PATH") ?: error("set GEMMA_GGUF_PATH")
    val engine = LlamaCppInferenceEngine()
    System.err.println("[smoke] loading $modelPath")
    val handle = engine.loadModel(modelPath, InferenceConfig(accelerator = com.contextsolutions.mobileagent.inference.Accelerator.CPU))
    System.err.println("[smoke] loaded on ${handle.activeAccelerator}; generating (cap 48)…")
    val req = GenerationRequest(prompt = "The capital of France is", maxTokens = 48)
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
    kotlin.system.exitProcess(0) // net.ladenthin:llama leaves native threads alive; force exit
}
