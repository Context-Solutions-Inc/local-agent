package com.contextsolutions.mobileagent.inference

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow

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

    override suspend fun loadModel(modelPath: String, config: InferenceConfig): ModelHandle {
        // WARNING level keeps Logcat clean in production; bump to INFO when
        // debugging perf or accelerator selection on the M0 spike.
        Engine.setNativeMinLogSeverity(LogSeverity.WARNING)

        val backend = resolveBackend(config.accelerator)
        val engineConfig = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            // PRD §4.2 sizes the KV cache for 8K-token contexts; LiteRT-LM exposes
            // this as a model-level cap on prefill + decode tokens.
            maxNumTokens = config.kvCacheTokens,
            // GPU delegate caches compiled kernels here; reused across loads to
            // avoid the multi-second recompile on every cold start.
            cacheDir = context.cacheDir.absolutePath,
        )
        val engine = Engine(engineConfig)
        engine.initialize()

        return LiteRtModelHandle(
            modelId = modelPath,
            loadedAtEpochMs = System.currentTimeMillis(),
            engine = engine,
            config = config,
        )
    }

    override fun unload(handle: ModelHandle) {
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
    }

    private fun resolveBackend(accelerator: Accelerator): Backend = when (accelerator) {
        // null lets LiteRT-LM pick a sensible thread count (typically num cores).
        Accelerator.CPU -> Backend.CPU(/* numOfThreads = */ null)
        Accelerator.GPU -> Backend.GPU()
        Accelerator.NPU -> Backend.NPU(
            nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
        )
        // AUTO: GPU is the right Pixel 7 default (Mali-G710). NPU support for
        // Tensor G2 EdgeTPU via LiteRT-LM is unconfirmed (PHASE1_PLAN §6 risk);
        // run the M0 spike with explicit Accelerator.NPU to validate before
        // changing this default.
        Accelerator.AUTO -> Backend.GPU()
    }

    private data class LiteRtModelHandle(
        override val modelId: String,
        override val loadedAtEpochMs: Long,
        val engine: Engine,
        val config: InferenceConfig,
    ) : ModelHandle
}

/**
 * Hilt provides a [LiteRtInferenceEngine] via this factory because [Context] can't
 * be passed through the [InferenceEngine] interface (which lives in commonMain
 * and can't reference Android types). Same pattern as [com.contextsolutions.mobileagent.platform.SecureStorageFactory].
 */
object LiteRtInferenceEngineFactory {
    fun create(context: Context): InferenceEngine = LiteRtInferenceEngine(context.applicationContext)
}
