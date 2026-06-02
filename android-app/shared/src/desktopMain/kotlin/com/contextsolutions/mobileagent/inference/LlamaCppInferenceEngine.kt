package com.contextsolutions.mobileagent.inference

import net.ladenthin.llama.InferenceParameters
import net.ladenthin.llama.LlamaModel
import net.ladenthin.llama.LlamaOutput
import net.ladenthin.llama.ModelParameters
import net.ladenthin.llama.Pair as LlamaPair
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Desktop (JVM) [InferenceEngine] backed by llama.cpp via the `net.ladenthin:llama`
 * JNI binding (GGUF Gemma). The Android counterpart is `LiteRtInferenceEngine`
 * (LiteRT-LM); this is the JVM seam fill for the Linux/macOS/Windows port
 * (docs/DESKTOP_PORT_PLAN.md, Phase 4). The agent loop is unaware of the
 * backend — only [InferenceEngine] crosses the boundary.
 *
 * Backend (CUDA / Metal / Vulkan / CPU) is selected at the **native-library**
 * level: the default `net.ladenthin:llama` artifact bundles CPU natives; a GPU build
 * (e.g. the CUDA-classified artifact, or a locally built `libjllama`) enables GPU
 * offload when [InferenceConfig.accelerator] requests it via `setGpuLayers`.
 *
 * **Threading.** `LlamaModel` is not safe for concurrent generation, so all
 * native calls are serialized onto a single-slot dispatcher and run off the
 * caller's thread (mirrors CLAUDE.md invariant #1).
 */
class LlamaCppInferenceEngine(
    /** GPU layers to offload when the accelerator is AUTO/GPU. 999 ⇒ "all layers". */
    private val gpuLayersWhenAccelerated: Int = 999,
    /** Diagnostic sink for load/backend lines (desktop has no logcat; default no-op). */
    private val logger: (String) -> Unit = {},
) : InferenceEngine {

    // Single-slot dispatcher: llama.cpp generation on a context must be serialized.
    private val llamaDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    override suspend fun loadModel(modelPath: String, config: InferenceConfig): ModelHandle =
        withContext(llamaDispatcher) {
            val useGpu = config.accelerator == Accelerator.AUTO || config.accelerator == Accelerator.GPU
            val gpuLayers = if (useGpu) gpuLayersWhenAccelerated else 0

            val params = ModelParameters()
                .setModel(modelPath)
                .setCtxSize(config.kvCacheTokens)
                .setGpuLayers(gpuLayers)
                .setTemp(config.temperature)
                .setTopK(config.topK)
                .setTopP(config.topP)
                // Use the GGUF's embedded (Gemma) chat template for setMessages().
                .enableJinja()

            // The host OS determines which GPU backend a *GPU-capable* native build would use
            // (the default `net.ladenthin:llama` artifact is CPU-only — Phase 0 finding). This is
            // the intended backend for diagnostics only; we can't query actual layer offload
            // from the Java binding, so [ModelHandle.activeAccelerator] below stays best-effort.
            val intendedBackend = if (useGpu) hostGpuBackend() else "CPU"
            logger(
                "loadModel gpuLayers=$gpuLayers ctx=${config.kvCacheTokens} " +
                    "intendedBackend=$intendedBackend (actual offload depends on the native build)",
            )

            val model = LlamaModel(params)
            // setGpuLayers > 0 requests offload; whether it lands on GPU depends on the native
            // build. Best-effort report (a GPU build → GPU). Accurate detection awaits Phase 8,
            // when GPU-classified natives ship and a real probe can replace this.
            val active = if (gpuLayers > 0) Accelerator.GPU else Accelerator.CPU
            LlamaModelHandle(
                model = model,
                modelId = modelPath.substringAfterLast('/'),
                loadedAtEpochMs = System.currentTimeMillis(),
                activeAccelerator = active,
            )
        }

    override fun unload(handle: ModelHandle) {
        (handle as? LlamaModelHandle)?.model?.close()
    }

    override fun generate(
        handle: ModelHandle,
        request: GenerationRequest,
        toolDispatcher: ToolDispatcher?,
    ): Flow<GenerationEvent> = flow {
        val model = (handle as LlamaModelHandle).model
        val params = buildInferenceParameters(model, request)

        val iterator = model.generate(params).iterator()
        // Reasoning GGUFs (e.g. the Gemma 4 E4B thinking build) stream a
        // <|channel>thought…<channel|> block before the answer; strip it so the
        // desktop chat shows just the answer (no-op for non-thinking models).
        val stripper = ThinkingStripper()
        var generated = 0
        var chunkIndex = 0
        try {
            while (iterator.hasNext()) {
                // Between-token cancellation: a cancelled collector aborts the loop and
                // the catch below cancels the native task. (The blocking next() itself
                // is not interruptible mid-token — acceptable for per-token streaming.)
                currentCoroutineContext().ensureActive()
                val out: LlamaOutput = iterator.next()
                if (out.text.isNotEmpty()) {
                    generated++
                    val visible = stripper.push(out.text)
                    if (visible.isNotEmpty()) emit(GenerationEvent.TokenChunk(visible, chunkIndex++))
                }
            }
            val tail = stripper.finish()
            if (tail.isNotEmpty()) emit(GenerationEvent.TokenChunk(tail, chunkIndex++))
            val reason =
                if (generated >= request.maxTokens) FinishReason.MAX_TOKENS else FinishReason.END_OF_TURN
            emit(GenerationEvent.Done(totalTokens = generated, finishReason = reason))
        } catch (c: CancellationException) {
            // Collector cancelled mid-decode — signal the native side to stop holding the
            // CPU/GPU, then propagate so the coroutine actually cancels. Best-effort;
            // analogous to LiteRT-LM's cancelProcess() (CLAUDE.md invariant #29).
            runCatching { iterator.cancel() }
            throw c
        } catch (t: Throwable) {
            // Contract: the engine emits exactly one terminal Done OR Error — never lets a
            // raw exception escape the flow (parity with LiteRtInferenceEngine, which routes
            // failures through GenerationEvent.Error so AgentLoop can surface a friendly
            // message / attempt tool-marker recovery).
            runCatching { iterator.cancel() }
            emit(GenerationEvent.Error(t.message ?: "llama.cpp generation failed", t))
        }
    }.flowOn(llamaDispatcher)

    private fun buildInferenceParameters(model: LlamaModel, request: GenerationRequest): InferenceParameters {
        val sampling = request.sampling
        val params: InferenceParameters

        if (request.history.isNotEmpty()) {
            // Structured path: render the system instruction + role/content history through
            // the GGUF's own chat template (Gemma framing applied natively via Jinja), then
            // generate on the rendered prompt. `model.generate` consumes `prompt`, not the
            // raw `messages` array, so we must materialize the template first via
            // applyTemplate — otherwise it completes the empty prompt and stops at 0 tokens.
            val messages = request.history.map { msg ->
                LlamaPair(msg.role.toLlamaRole(), msg.text)
            }
            val templateParams = InferenceParameters("")
                .setMessages(request.systemInstruction ?: "", messages)
            val renderedPrompt = model.applyTemplate(templateParams)
            params = InferenceParameters(renderedPrompt)
        } else {
            // Legacy raw path (spike/tests): single prompt, no chat-template framing.
            params = InferenceParameters(request.prompt)
        }

        params.setNPredict(request.maxTokens)
        if (request.stopSequences.isNotEmpty()) {
            params.setStopStrings(*request.stopSequences.toTypedArray())
        }
        if (sampling != null) {
            // GREEDY (topK=1) ⇒ argmax; preserves verbatim factual copying on
            // search-grounded turns (InferenceEngine.SamplingParams docs, invariant #36).
            params.setTemperature(sampling.temperature)
                .setTopK(sampling.topK)
                .setTopP(sampling.topP)
        }
        return params
    }
}

/**
 * The GPU backend a GPU-capable llama.cpp build would target on this host: Metal on macOS,
 * CUDA on Linux/Windows (NVIDIA — the common discrete-GPU case), with Vulkan the cross-vendor
 * fallback. Diagnostic label only — the bundled artifact is CPU-only, and packaging the GPU
 * natives + the real per-vendor selection is Phase 8.
 */
private fun hostGpuBackend(): String {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        os.contains("mac") || os.contains("darwin") -> "Metal"
        os.contains("win") || os.contains("nux") || os.contains("nix") -> "CUDA/Vulkan"
        else -> "Vulkan"
    }
}

private fun HistoryRole.toLlamaRole(): String = when (this) {
    HistoryRole.SYSTEM -> "system"
    HistoryRole.USER -> "user"
    HistoryRole.MODEL -> "assistant"
    // Gemma's template has no dedicated tool role; surface tool results as a user turn.
    // In practice the agent loop dispatches tools before the model, so TOOL turns rarely
    // reach the engine.
    HistoryRole.TOOL -> "user"
}

private class LlamaModelHandle(
    val model: LlamaModel,
    override val modelId: String,
    override val loadedAtEpochMs: Long,
    override val activeAccelerator: Accelerator,
) : ModelHandle
