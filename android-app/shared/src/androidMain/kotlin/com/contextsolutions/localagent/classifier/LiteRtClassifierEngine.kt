package com.contextsolutions.localagent.classifier

import android.content.Context
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [ClassifierEngine] backed by AI Edge LiteRT 2.x
 * (`com.google.ai.edge.litert:litert`), the Android port of Python's
 * `ai-edge-litert` runtime. We run the
 * `preflight_memory_shared_v1.0.0_int8.tflite` artifact bundled at
 * `:androidApp/src/main/assets/`, requesting GPU first and falling back to
 * CPU if the GPU accelerator isn't available.
 *
 * **Why ai-edge-litert and not the classic TFLite Java runtime.** The
 * Play Services-delivered TFLite runtime (`FROM_SYSTEM_ONLY`) AND the
 * bundled `org.tensorflow:tensorflow-lite:2.16.x` both produce numerically
 * broken outputs for our `ai-edge-quantizer` weight-only INT8 model —
 * logits ~1500x larger magnitude than the Python reference, every query
 * collapsing to a single class. Verified by the M4 Phase B diagnostic.
 * `ai-edge-litert` is a different runtime (its own `libLiteRt.so` plus
 * vendor accelerator plugins) that matches the export-time toolchain.
 * LiteRT-LM (Gemma 4) continues to use its own runtime via Play Services.
 *
 * **Output dispatch.** v1.0 has no signatures, so we identify each output
 * by its trace-time `StatefulPartitionedCall:N` name (parsed at warmup).
 * The new API exposes the names via `model.getOutputTensorType(name)`,
 * but `createOutputBuffers()` returns a positional `List<TensorBuffer>`
 * so we still need to discover which positional index corresponds to
 * each named head. We do that empirically by shape (each head has a
 * unique shape: `[1,3]`/`[1,2]`/`[1,6]`) which is the simplest robust
 * approach with this API.
 *
 * **Threading.** `warmUp`, `classify`, `unload` all run on `Dispatchers.IO`;
 * mutable state is guarded by a [Mutex] so concurrent first-use callers
 * don't race the warmup.
 *
 * **Failure mode.** [warmUp] never throws — accelerator init failure,
 * asset missing, or model load failure all return `null`. The upstream
 * router degrades to standard Gemma tool-calling per PRD §3.2.1.
 */
class LiteRtClassifierEngine(
    private val context: Context,
    private val modelAssetPath: String = DEFAULT_MODEL_ASSET_PATH,
) : ClassifierEngine {

    private val mutex = Mutex()

    @Volatile private var environment: Environment? = null
    @Volatile private var model: CompiledModel? = null
    @Volatile private var accelerator: ClassifierAccelerator? = null

    // Resolved at warmup so classify() doesn't re-discover them every call.
    @Volatile private var preflightOutputIndex: Int = -1
    @Volatile private var presenceOutputIndex: Int = -1
    @Volatile private var categoryOutputIndex: Int = -1

    override val isLoaded: Boolean get() = model != null

    override suspend fun warmUp(): ClassifierAccelerator? = mutex.withLock {
        if (model != null) return@withLock accelerator
        withContext(Dispatchers.IO) {
            try {
                val env = Environment.create()
                val (compiled, chosen) = createModel(env)

                // Discover output buffer ordering by running once on zeros and
                // matching shapes. Each head has a unique shape, so shape
                // alone identifies the slot. Read-only allocation; the next
                // real classify call replaces these buffers.
                val zeros = LongArray(WordPieceTokenizer.MAX_SEQUENCE_LENGTH)
                resolveOutputIndicesByShape(compiled, zeros)

                this@LiteRtClassifierEngine.environment = env
                this@LiteRtClassifierEngine.model = compiled
                this@LiteRtClassifierEngine.accelerator = chosen
                Log.i(TAG, "classifier loaded on $chosen (model=$modelAssetPath)")
                chosen
            } catch (t: Throwable) {
                Log.e(TAG, "classifier warmUp failed; agent will fall through to Gemma", t)
                cleanupOnFailure()
                null
            }
        }
    }

    override suspend fun classify(
        inputIds: LongArray,
        attentionMask: LongArray,
    ): ClassifierOutput? {
        require(inputIds.size == WordPieceTokenizer.MAX_SEQUENCE_LENGTH) {
            "inputIds must be ${WordPieceTokenizer.MAX_SEQUENCE_LENGTH}, was ${inputIds.size}"
        }
        require(attentionMask.size == WordPieceTokenizer.MAX_SEQUENCE_LENGTH) {
            "attentionMask must be ${WordPieceTokenizer.MAX_SEQUENCE_LENGTH}, was ${attentionMask.size}"
        }
        val compiled = model ?: return null
        return withContext(Dispatchers.IO) {
            try {
                runForward(compiled, inputIds, attentionMask)
            } catch (t: Throwable) {
                Log.e(TAG, "classify failed", t)
                null
            }
        }
    }

    override suspend fun unload(): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            model?.runCatching { close() }
            environment?.runCatching { close() }
            model = null
            environment = null
            accelerator = null
            preflightOutputIndex = -1
            presenceOutputIndex = -1
            categoryOutputIndex = -1
        }
    }

    // -- Internal helpers ---------------------------------------------------

    private fun cleanupOnFailure() {
        model?.runCatching { close() }
        environment?.runCatching { close() }
        model = null
        environment = null
        accelerator = null
    }

    /**
     * Try GPU first, fall back to CPU on any error. The classic Play Services
     * GPU delegate refused our model (BROADCAST_TO / EMBEDDING_LOOKUP / int64
     * CAST not supported); ai-edge-litert's GPU may also fail for the same
     * reason. CPU XNNPACK is the documented fallback per
     * `docs/M3_M4_HANDOFF.md` §5 and still hits the 80 ms target.
     */
    private fun createModel(env: Environment): Pair<CompiledModel, ClassifierAccelerator> {
        // PR #3 — the .tflite is no longer bundled in the APK; it's downloaded
        // from the CDN on first run into filesDir/models/ (the first-run gate
        // ensures it's present before chat). If it's somehow absent, throw so
        // warmUp() catches it and degrades to Gemma-only (no search/memory).
        val file = File(context.filesDir, "models/$modelAssetPath")
        require(file.isFile) { "classifier model not found at ${file.absolutePath}" }
        Log.i(TAG, "loading classifier from filesDir (${file.absolutePath})")
        fun compile(options: CompiledModel.Options): CompiledModel =
            CompiledModel.create(file.absolutePath, options, env)
        try {
            return compile(CompiledModel.Options(Accelerator.GPU)) to ClassifierAccelerator.GPU
        } catch (t: Throwable) {
            Log.w(TAG, "GPU init failed; falling back to CPU XNNPACK (${t.message})")
        }
        return compile(CompiledModel.Options(Accelerator.CPU)) to ClassifierAccelerator.CPU
    }

    /**
     * Resolve which positional output buffer index corresponds to each head.
     * Each head's shape is unique (`[1,3]` preflight, `[1,2]` presence,
     * `[1,6]` category), so shape alone disambiguates. We run a one-shot
     * forward pass on zeros to get the buffers, then close them — production
     * classify() allocates fresh buffers each call.
     */
    private fun resolveOutputIndicesByShape(
        compiled: CompiledModel,
        zeros: LongArray,
    ) {
        val inputs = compiled.createInputBuffers()
        val outputs = compiled.createOutputBuffers()
        try {
            require(inputs.size == 2) { "expected 2 inputs, got ${inputs.size}" }
            require(outputs.size == 3) { "expected 3 outputs, got ${outputs.size}" }

            // Write zeros to both inputs so we can run once and inspect output sizes.
            inputs[0].writeLong(zeros)
            inputs[1].writeLong(zeros)
            compiled.run(inputs, outputs)

            var preflight = -1
            var presence = -1
            var category = -1
            for (i in outputs.indices) {
                val n = outputs[i].readFloat().size
                when (n) {
                    ClassifierOutput.PREFLIGHT_CLASSES -> preflight = i
                    ClassifierOutput.PRESENCE_CLASSES -> presence = i
                    ClassifierOutput.CATEGORY_CLASSES -> category = i
                }
            }
            require(preflight >= 0 && presence >= 0 && category >= 0) {
                "could not map outputs by shape — sizes were ${outputs.map { it.readFloat().size }}"
            }
            preflightOutputIndex = preflight
            presenceOutputIndex = presence
            categoryOutputIndex = category
        } finally {
            inputs.forEach { it.runCatching { close() } }
            outputs.forEach { it.runCatching { close() } }
        }
    }

    private fun runForward(
        compiled: CompiledModel,
        inputIds: LongArray,
        attentionMask: LongArray,
    ): ClassifierOutput {
        val inputs = compiled.createInputBuffers()
        val outputs = compiled.createOutputBuffers()
        try {
            // The new API allocates input buffers in the model's declared order.
            // The .tflite has `serving_default_args_0:0` (input_ids) before
            // `serving_default_args_1:0` (attention_mask). If a future re-export
            // shifts that order, classify() would feed swapped inputs without
            // an exception — the e2e test in :androidApp/src/androidTest/ guards
            // this by asserting calibrated probabilities for known queries.
            inputs[0].writeLong(inputIds)
            inputs[1].writeLong(attentionMask)
            compiled.run(inputs, outputs)

            return ClassifierOutput(
                preflightLogits = outputs[preflightOutputIndex].readFloat(),
                presenceLogits = outputs[presenceOutputIndex].readFloat(),
                categoryLogits = outputs[categoryOutputIndex].readFloat(),
            )
        } finally {
            inputs.forEach { it.runCatching { close() } }
            outputs.forEach { it.runCatching { close() } }
        }
    }

    companion object {
        private const val TAG = "ClassifierEngine"
        const val DEFAULT_MODEL_ASSET_PATH: String = "preflight_memory_shared_v1.0.0_int8.tflite"
    }
}
