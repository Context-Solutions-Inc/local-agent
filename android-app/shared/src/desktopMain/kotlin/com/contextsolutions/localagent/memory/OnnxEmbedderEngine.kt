package com.contextsolutions.localagent.memory

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.contextsolutions.localagent.classifier.WordPieceTokenizer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Desktop [EmbedderEngine] backed by ONNX Runtime (Java). Counterpart to the
 * Android `LiteRtEmbedderEngine` — the `all-MiniLM-L6-v2` encoder with
 * mean-pool + L2-norm baked into the graph, re-exported to ONNX
 * (`export_minilm_onnx.py`, Phase 5) because `ai-edge-litert` is Android-only
 * (CLAUDE.md invariant #18). The single output is already a normalised 384-dim
 * sentence vector, so cosine similarity is a plain dot product.
 *
 * **Single output, resolved generically.** Unlike the 3-head classifier the
 * MiniLM graph has exactly one output; we read whichever name the export
 * emitted (`OrtSession.outputNames.single()`) rather than hardcoding it.
 * [EmbedderOutput]'s `init` enforces the 384-dim contract, so a re-export with
 * a different hidden size fails at warmup instead of corrupting the BLOB column.
 *
 * **Sequence length (invariant #15) / tokenizer (invariant #13).** Statically
 * 128, int64 `input_ids`/`attention_mask`; tokenisation is internal via the
 * shared [WordPieceTokenizer] (same `vocab.txt` as the classifier).
 *
 * **Accelerator / threading / failure mode** mirror [OnnxClassifierEngine]:
 * CUDA EP first then CPU fallback; `Dispatchers.IO` under a [Mutex]; [warmUp]
 * never throws — a missing model, init failure, or malformed graph return
 * `null` and the memory subsystem degrades to no-op (PRD §3.2.4).
 */
class OnnxEmbedderEngine(
    private val tokenizer: WordPieceTokenizer,
    private val modelPath: File,
    private val logger: (String) -> Unit = {},
) : EmbedderEngine {

    private val mutex = Mutex()

    @Volatile private var session: OrtSession? = null
    @Volatile private var accelerator: EmbedderAccelerator? = null
    @Volatile private var outputName: String = ""

    override val isLoaded: Boolean get() = session != null

    override suspend fun warmUp(): EmbedderAccelerator? = mutex.withLock {
        if (session != null) return@withLock accelerator
        withContext(Dispatchers.IO) {
            if (!modelPath.isFile) {
                logger("embedder model absent at $modelPath; memory subsystem degrades to no-op")
                return@withContext null
            }
            try {
                val (ortSession, chosen) = createSession()
                this@OnnxEmbedderEngine.outputName = resolveOutputName(ortSession)
                verifyOutputShape(ortSession)
                this@OnnxEmbedderEngine.session = ortSession
                this@OnnxEmbedderEngine.accelerator = chosen
                logger("embedder loaded on $chosen (model=${modelPath.name})")
                chosen
            } catch (t: Throwable) {
                logger("embedder warmUp failed; memory subsystem degrades to no-op: ${t.message}")
                cleanupOnFailure()
                null
            }
        }
    }

    override suspend fun embed(text: String): EmbedderOutput? {
        val s = session ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val tokenized = tokenizer.encodeSingle(text)
                runForward(s, tokenized.inputIds, tokenized.attentionMask)
            } catch (t: Throwable) {
                logger("embed failed for text length=${text.length}: ${t.message}")
                null
            }
        }
    }

    override suspend fun unload(): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            session?.runCatching { close() }
            session = null
            accelerator = null
            outputName = ""
        }
    }

    // -- Internal helpers ---------------------------------------------------

    private fun cleanupOnFailure() {
        session?.runCatching { close() }
        session = null
        accelerator = null
        outputName = ""
    }

    private fun createSession(): Pair<OrtSession, EmbedderAccelerator> {
        val env = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { gpuOpts ->
            try {
                gpuOpts.addCUDA(0)
                return env.createSession(modelPath.absolutePath, gpuOpts) to EmbedderAccelerator.GPU
            } catch (t: Throwable) {
                logger("CUDA EP unavailable; falling back to CPU (${t.message})")
            }
        }
        OrtSession.SessionOptions().use { cpuOpts ->
            return env.createSession(modelPath.absolutePath, cpuOpts) to EmbedderAccelerator.CPU
        }
    }

    private fun resolveOutputName(s: OrtSession): String {
        require(REQUIRED_INPUTS.all { it in s.inputNames }) {
            "missing inputs $REQUIRED_INPUTS; inputs=${s.inputNames}"
        }
        require(s.outputNames.size == 1) {
            "embedder must expose exactly 1 output (sentence vector), got ${s.outputNames}"
        }
        return s.outputNames.single()
    }

    /**
     * Run a one-shot zeros pass and assert the output is exactly
     * [EmbedderOutput.EMBEDDING_DIM] floats — catches a re-export with a
     * mismatched hidden size at load rather than at every embed call.
     */
    private fun verifyOutputShape(s: OrtSession) {
        val zeros = LongArray(WordPieceTokenizer.MAX_SEQUENCE_LENGTH)
        runForward(s, zeros, zeros) // EmbedderOutput.init enforces the 384-dim contract.
    }

    private fun runForward(
        s: OrtSession,
        inputIds: LongArray,
        attentionMask: LongArray,
    ): EmbedderOutput {
        require(inputIds.size == WordPieceTokenizer.MAX_SEQUENCE_LENGTH) {
            "inputIds must be ${WordPieceTokenizer.MAX_SEQUENCE_LENGTH}, was ${inputIds.size}"
        }
        require(attentionMask.size == WordPieceTokenizer.MAX_SEQUENCE_LENGTH) {
            "attentionMask must be ${WordPieceTokenizer.MAX_SEQUENCE_LENGTH}, was ${attentionMask.size}"
        }
        val env = OrtEnvironment.getEnvironment()
        val idsTensor = OnnxTensor.createTensor(env, arrayOf(inputIds))
        val maskTensor = OnnxTensor.createTensor(env, arrayOf(attentionMask))
        try {
            s.run(mapOf(INPUT_IDS to idsTensor, ATTENTION_MASK to maskTensor)).use { result ->
                val value = result.get(outputName).orElseThrow {
                    IllegalStateException("output '$outputName' absent at run")
                }
                // Shape [1, 384] float32 — take row 0.
                @Suppress("UNCHECKED_CAST")
                val matrix = (value as OnnxTensor).value as Array<FloatArray>
                return EmbedderOutput(matrix[0])
            }
        } finally {
            idsTensor.runCatching { close() }
            maskTensor.runCatching { close() }
        }
    }

    companion object {
        const val INPUT_IDS: String = "input_ids"
        const val ATTENTION_MASK: String = "attention_mask"

        private val REQUIRED_INPUTS = listOf(INPUT_IDS, ATTENTION_MASK)
    }
}
