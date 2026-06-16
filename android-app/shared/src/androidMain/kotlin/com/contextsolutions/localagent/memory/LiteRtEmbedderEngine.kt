package com.contextsolutions.localagent.memory

import android.content.Context
import android.util.Log
import com.contextsolutions.localagent.classifier.WordPieceTokenizer
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [EmbedderEngine] backed by AI Edge LiteRT 2.x
 * (`com.google.ai.edge.litert:litert`) — same runtime that hosts the
 * pre-flight classifier (M4). The shipped artifact is
 * `all-MiniLM-L6-v2_int8.tflite` (23.5 MB, INT8 weight-only). Mean-pool +
 * L2-norm are baked into the graph by the export driver
 * (`classifier-training/scripts/export_minilm_litert.py`), so the single
 * output tensor is already a normalised 384-dim sentence vector.
 *
 * **Why ai-edge-litert and not Play Services TFLite.** Same reason as the
 * classifier (CLAUDE.md inv. #18): the export tooling targets ai-edge-litert
 * runtime semantics and Play Services TFLite produced numerically broken
 * outputs for the classifier graph. Using one runtime for both encoder
 * graphs keeps the failure modes shared.
 *
 * **GPU vs CPU.** Tries GPU first for parity with [LiteRtClassifierEngine].
 * Phase A confirmed the embedder graph is also GPU-rejected on Pixel 7
 * (44/366 ops unsupported, "Failed to compile model"); falls back to CPU
 * XNNPACK without throwing.
 *
 * **Threading.** [warmUp], [embed], [unload] all run on `Dispatchers.IO`;
 * mutable state is guarded by a [Mutex] so concurrent first-use callers
 * don't race the warmup.
 *
 * **Failure mode.** [warmUp] never throws — accelerator init, asset
 * missing, or model load failure all return `null`. The upstream
 * `MemoryRetriever` / `MemoryExtractor` degrade to no-op (PRD §3.2.4).
 */
class LiteRtEmbedderEngine(
    private val context: Context,
    private val tokenizer: WordPieceTokenizer,
    private val modelAssetPath: String = DEFAULT_MODEL_ASSET_PATH,
) : EmbedderEngine {

    private val mutex = Mutex()

    @Volatile private var environment: Environment? = null
    @Volatile private var model: CompiledModel? = null
    @Volatile private var accelerator: EmbedderAccelerator? = null

    override val isLoaded: Boolean get() = model != null

    override suspend fun warmUp(): EmbedderAccelerator? = mutex.withLock {
        if (model != null) return@withLock accelerator
        withContext(Dispatchers.IO) {
            try {
                val env = Environment.create()
                val (compiled, chosen) = createModel(env)

                // One-shot zeros pass to confirm output shape matches the
                // [1, 384] contract baked at export time. A future re-export
                // that changes the embedding dim would otherwise corrupt
                // every memory write silently.
                verifyOutputShape(compiled)

                this@LiteRtEmbedderEngine.environment = env
                this@LiteRtEmbedderEngine.model = compiled
                this@LiteRtEmbedderEngine.accelerator = chosen
                Log.i(TAG, "embedder loaded on $chosen (model=$modelAssetPath)")
                chosen
            } catch (t: Throwable) {
                Log.e(TAG, "embedder warmUp failed; memory subsystem will degrade to no-op", t)
                cleanupOnFailure()
                null
            }
        }
    }

    override suspend fun embed(text: String): EmbedderOutput? {
        val compiled = model ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val tokenized = tokenizer.encodeSingle(text)
                runForward(compiled, tokenized.inputIds, tokenized.attentionMask)
            } catch (t: Throwable) {
                Log.e(TAG, "embed failed for text length=${text.length}", t)
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

    private fun createModel(env: Environment): Pair<CompiledModel, EmbedderAccelerator> {
        // PR #58 — prefer a copy pushed to filesDir/models/ (dev builds with
        // `-PexternalModels` skip bundling the .tflite in the APK so installs stay
        // small); fall back to the bundled asset (the production path).
        val externalFile = File(context.filesDir, "models/$modelAssetPath").takeIf { it.isFile }
        if (externalFile != null) {
            Log.i(TAG, "loading embedder from filesDir (${externalFile.absolutePath})")
        }
        fun compile(options: CompiledModel.Options): CompiledModel =
            if (externalFile != null) {
                CompiledModel.create(externalFile.absolutePath, options, env)
            } else {
                CompiledModel.create(context.assets, modelAssetPath, options, env)
            }
        try {
            return compile(CompiledModel.Options(Accelerator.GPU)) to EmbedderAccelerator.GPU
        } catch (t: Throwable) {
            Log.w(TAG, "GPU init failed; falling back to CPU XNNPACK (${t.message})")
        }
        return compile(CompiledModel.Options(Accelerator.CPU)) to EmbedderAccelerator.CPU
    }

    /**
     * Run a one-shot forward pass on zeros and assert the output buffer is
     * exactly [Memory.EMBEDDING_DIM] floats. Catches a re-exported model
     * with mismatched hidden size at load time rather than at every embed
     * call (which would silently corrupt the BLOB column).
     */
    private fun verifyOutputShape(compiled: CompiledModel) {
        val inputs = compiled.createInputBuffers()
        val outputs = compiled.createOutputBuffers()
        try {
            require(inputs.size == 2) { "expected 2 inputs (input_ids, attention_mask), got ${inputs.size}" }
            require(outputs.size == 1) { "expected 1 output (sentence vector), got ${outputs.size}" }
            inputs[0].writeLong(LongArray(WordPieceTokenizer.MAX_SEQUENCE_LENGTH))
            inputs[1].writeLong(LongArray(WordPieceTokenizer.MAX_SEQUENCE_LENGTH))
            compiled.run(inputs, outputs)
            val actual = outputs[0].readFloat()
            require(actual.size == Memory.EMBEDDING_DIM) {
                "embedder output dim mismatch: expected ${Memory.EMBEDDING_DIM}, got ${actual.size}. " +
                    "Re-export with --hidden=${Memory.EMBEDDING_DIM} or update Memory.EMBEDDING_DIM."
            }
        } finally {
            inputs.forEach { it.runCatching { close() } }
            outputs.forEach { it.runCatching { close() } }
        }
    }

    private fun runForward(
        compiled: CompiledModel,
        inputIds: LongArray,
        attentionMask: LongArray,
    ): EmbedderOutput {
        require(inputIds.size == WordPieceTokenizer.MAX_SEQUENCE_LENGTH) {
            "inputIds must be ${WordPieceTokenizer.MAX_SEQUENCE_LENGTH}, was ${inputIds.size}"
        }
        require(attentionMask.size == WordPieceTokenizer.MAX_SEQUENCE_LENGTH) {
            "attentionMask must be ${WordPieceTokenizer.MAX_SEQUENCE_LENGTH}, was ${attentionMask.size}"
        }
        val inputs = compiled.createInputBuffers()
        val outputs = compiled.createOutputBuffers()
        try {
            // Positional dispatch: [0]=input_ids, [1]=attention_mask. Mirrors
            // LiteRtClassifierEngine; the export consistently puts inputs in
            // this order. If a future re-export shifts the order the
            // EmbedderEndToEndTest catches it via byte-near-exact comparison
            // against the host fixture vectors.
            inputs[0].writeLong(inputIds)
            inputs[1].writeLong(attentionMask)
            compiled.run(inputs, outputs)
            return EmbedderOutput(outputs[0].readFloat())
        } finally {
            inputs.forEach { it.runCatching { close() } }
            outputs.forEach { it.runCatching { close() } }
        }
    }

    companion object {
        private const val TAG = "EmbedderEngine"
        const val DEFAULT_MODEL_ASSET_PATH: String = "all-MiniLM-L6-v2_int8.tflite"
    }
}
