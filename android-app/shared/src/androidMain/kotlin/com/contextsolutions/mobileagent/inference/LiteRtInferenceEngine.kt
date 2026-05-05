package com.contextsolutions.mobileagent.inference

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Production [InferenceEngine] backed by LiteRT-LM. The single seam between the
 * agent loop (commonMain) and LiteRT-LM lives here — every LiteRT-LM type stays
 * inside this file, per PHASE1_PLAN §3.3.
 *
 * Threading: LiteRT-LM is internally synchronous and exposes its async surface
 * via Kotlin Flow, which this implementation forwards directly. Cancelling the
 * collecting coroutine cancels the underlying generation.
 *
 * Lifecycle: [loadModel] holds an [Engine] for the lifetime of the returned
 * [ModelHandle]; [unload] closes it. A fresh [com.google.ai.edge.litertlm.Conversation]
 * is created per [generate] call (use {} closes it on completion or error).
 *
 * Phase 1 limitations:
 * - Function-call parsing is not yet wired. LiteRT-LM 0.10.2's ConversationConfig
 *   exposes a `tools` list and `automaticToolCalling` flag — when M3's pre-flight
 *   + tool-calling work lands, those plug in here and emit [GenerationEvent.FunctionCall].
 * - [GenerationRequest.stopSequences] is not yet honored — no stop-sequence
 *   surface on ConversationConfig. Generation runs until end-of-turn.
 * - [GenerationRequest.maxTokens] is also not yet honored as a per-request cap;
 *   LiteRT-LM only exposes a model-level [EngineConfig.maxNumTokens] (see
 *   [InferenceConfig.kvCacheTokens] mapping).
 */
class LiteRtInferenceEngine(private val context: Context) : InferenceEngine {

    /**
     * Every blocking native call below MUST run off the main thread:
     * [Engine.initialize] alone takes 4–8s cold on Pixel 7, well past Android's
     * 5s ANR threshold. The previous version blocked the UI thread and was killed
     * by the OS as "not responding" before the model finished loading.
     *
     * loadModel + unload are wrapped in withContext(IO); generate's Flow uses
     * flowOn(IO) so both upstream emission AND downstream collect happen off main.
     */
    override suspend fun loadModel(
        modelPath: String,
        config: InferenceConfig,
    ): ModelHandle = withContext(Dispatchers.IO) {
        // WARNING level keeps Logcat clean in production; bump to INFO when
        // debugging perf or accelerator selection on the M0 spike.
        Engine.setNativeMinLogSeverity(LogSeverity.WARNING)

        val requested = resolveAccelerator(config.accelerator)
        val (engine, actual) = tryInitialize(modelPath, config, requested)

        LiteRtModelHandle(
            modelId = modelPath,
            loadedAtEpochMs = System.currentTimeMillis(),
            engine = engine,
            config = config,
            activeAccelerator = actual,
        )
    }

    /**
     * Initialise an [Engine] on [requested]. If the requested accelerator is GPU
     * and was selected via [Accelerator.AUTO] (i.e. caller didn't explicitly pin
     * GPU), and initialise() throws, retry on CPU.
     *
     * GPU init can fail at runtime even when our deps are correct — Play Services
     * TFLite isn't on every device (CN market, AOSP forks, GrapheneOS) and is the
     * source of `Cannot find OpenCL library on this device` (M0 memo §5 Risk 1).
     * CPU isn't fast enough for the relaxed Phase 1 perf targets but degraded
     * generation is strictly better than a hard load failure with no chat.
     *
     * Explicit [Accelerator.GPU] / [Accelerator.NPU] do NOT fall back — the spike
     * harness uses those to characterise specific accelerators and a silent
     * fallback would falsify its measurements.
     */
    private fun tryInitialize(
        modelPath: String,
        config: InferenceConfig,
        requested: Accelerator,
    ): Pair<Engine, Accelerator> {
        val first = newEngine(modelPath, config, requested)
        try {
            first.initialize()
            return first to requested
        } catch (t: Throwable) {
            // Always close the half-initialised engine before retrying — leaving
            // it open holds native handles that the CPU retry will collide with.
            runCatching { first.close() }
            if (config.accelerator != Accelerator.AUTO || requested == Accelerator.CPU) {
                throw t
            }
            android.util.Log.w(
                TAG,
                "GPU init failed (${t.message}); falling back to CPU. Generation will be slower.",
            )
            val fallback = newEngine(modelPath, config, Accelerator.CPU)
            try {
                fallback.initialize()
            } catch (t2: Throwable) {
                runCatching { fallback.close() }
                t2.addSuppressed(t)
                throw t2
            }
            return fallback to Accelerator.CPU
        }
    }

    private fun newEngine(modelPath: String, config: InferenceConfig, accelerator: Accelerator): Engine {
        val engineConfig = EngineConfig(
            modelPath = modelPath,
            backend = backendFor(accelerator),
            // PRD §4.2 sizes the KV cache for 8K-token contexts; LiteRT-LM exposes
            // this as a model-level cap on prefill + decode tokens.
            maxNumTokens = config.kvCacheTokens,
            // GPU delegate caches compiled kernels here; reused across loads to
            // avoid the multi-second recompile on every cold start.
            cacheDir = context.cacheDir.absolutePath,
        )
        return Engine(engineConfig)
    }

    override fun unload(handle: ModelHandle) {
        // Synchronous because the InferenceEngine contract is fire-and-forget for
        // unload, but the actual native call still blocks. Callers should invoke
        // from a background coroutine; see SpikeRunner which already does so via
        // its withContext(IO) wrapper.
        val typed = handle as? LiteRtModelHandle ?: return
        typed.engine.close()
    }

    override fun generate(handle: ModelHandle, request: GenerationRequest): Flow<GenerationEvent> = flow {
        val typed = handle as? LiteRtModelHandle
            ?: error("ModelHandle is not a LiteRtModelHandle; engine binding is wrong.")

        val samplerConfig = SamplerConfig(
            topK = typed.config.topK,
            topP = typed.config.topP.toDouble(),
            temperature = typed.config.temperature.toDouble(),
            // Random per generation; M1 plumbs an optional caller-supplied seed
            // through GenerationRequest for reproducibility in tests/eval.
            seed = (System.nanoTime() and 0x7FFFFFFF).toInt(),
        )
        val conversationConfig = ConversationConfig(samplerConfig = samplerConfig)

        var tokenIndex = 0
        typed.engine.createConversation(conversationConfig).use { conversation ->
            conversation.sendMessageAsync(request.prompt)
                .catch { t ->
                    emit(GenerationEvent.Error(message = t.message ?: "unknown error", cause = t))
                }
                .collect { chunk ->
                    // LiteRT-LM emits per-token (or per-small-chunk) text. The exact
                    // emission granularity is opaque; we forward each chunk as a
                    // TokenChunk and let the UI/agent loop handle whatever shape arrives.
                    val text = chunk.toString()
                    if (text.isNotEmpty()) {
                        emit(GenerationEvent.TokenChunk(text = text, tokenIndex = tokenIndex))
                        tokenIndex++
                    }
                }
        }
        emit(GenerationEvent.Done(totalTokens = tokenIndex, finishReason = FinishReason.END_OF_TURN))
    }.flowOn(Dispatchers.IO)

    /**
     * Maps [Accelerator.AUTO] to the concrete first-choice for the device.
     * Pixel 7 default is GPU (Mali-G710); NPU support for Tensor G2 EdgeTPU via
     * LiteRT-LM is unconfirmed (PHASE1_PLAN §6 risk). The CPU fallback in
     * [tryInitialize] handles the case where GPU init throws at runtime.
     */
    private fun resolveAccelerator(accelerator: Accelerator): Accelerator = when (accelerator) {
        Accelerator.AUTO -> Accelerator.GPU
        else -> accelerator
    }

    private fun backendFor(accelerator: Accelerator): Backend = when (accelerator) {
        // null lets LiteRT-LM pick a sensible thread count (typically num cores).
        Accelerator.CPU -> Backend.CPU(/* numOfThreads = */ null)
        Accelerator.GPU -> Backend.GPU()
        Accelerator.NPU -> Backend.NPU(
            nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
        )
        // AUTO is resolved to a concrete accelerator before we pick a backend;
        // hitting this branch is a programming error.
        Accelerator.AUTO -> error("AUTO must be resolved before calling backendFor")
    }

    private data class LiteRtModelHandle(
        override val modelId: String,
        override val loadedAtEpochMs: Long,
        override val activeAccelerator: Accelerator,
        val engine: Engine,
        val config: InferenceConfig,
    ) : ModelHandle

    private companion object {
        const val TAG = "LiteRtInferenceEngine"
    }
}

/**
 * Hilt provides a [LiteRtInferenceEngine] via this factory because [Context] can't
 * be passed through the [InferenceEngine] interface (which lives in commonMain
 * and can't reference Android types). Same pattern as [com.contextsolutions.mobileagent.platform.SecureStorageFactory].
 */
object LiteRtInferenceEngineFactory {
    fun create(context: Context): InferenceEngine = LiteRtInferenceEngine(context.applicationContext)
}
