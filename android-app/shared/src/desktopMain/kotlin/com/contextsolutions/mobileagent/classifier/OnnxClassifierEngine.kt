package com.contextsolutions.mobileagent.classifier

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Desktop [ClassifierEngine] backed by ONNX Runtime (Java),
 * `com.microsoft.onnxruntime:onnxruntime`. The Android engine runs the
 * `preflight_memory_shared_v1.0.0_int8.tflite` artifact on `ai-edge-litert`,
 * which is **Android-only** (CLAUDE.md invariant #18) — so desktop runs a
 * re-export of the same shared DistilBERT encoder + 3 task heads to ONNX
 * (`ct-export-onnx`, Phase 5, docs/DESKTOP_PORT_PLAN.md).
 *
 * **Output dispatch (invariant #12).** The Android `.tflite` exposes heads as
 * `StatefulPartitionedCall:N` and the engine disambiguates by shape because the
 * runtime permutes names↔indices. The ONNX export instead sets *explicit,
 * stable* output names ([PREFLIGHT_OUTPUT] / [PRESENCE_OUTPUT] /
 * [CATEGORY_OUTPUT]) and inputs ([INPUT_IDS] / [ATTENTION_MASK]), so we resolve
 * each head **by name** — `OrtSession.Result.get(name)` — never by positional
 * index. [warmUp] asserts all five names are present and runs a one-shot zeros
 * pass; [ClassifierOutput]'s own `init` then enforces the per-head sizes
 * `[3]`/`[2]`/`[6]`, catching an export that drifts the signature.
 *
 * **Sequence length (invariant #15).** Statically 128, int64
 * `input_ids`/`attention_mask` — must match the export
 * (`ct-export-onnx --max-length 128`) and the shared [WordPieceTokenizer].
 *
 * **Accelerator.** Tries the CUDA execution provider; on any failure (the
 * bundled CPU-only `onnxruntime` artifact has no CUDA native, or no GPU
 * present) falls back to the default CPU EP. Mirrors the LLM GPU-probe path.
 *
 * **Threading.** [warmUp], [classify], [unload] all run on `Dispatchers.IO`;
 * mutable state is guarded by a [Mutex] so concurrent first-use callers don't
 * race the warmup.
 *
 * **Failure mode.** [warmUp] never throws — a missing model file, CUDA/CPU
 * init failure, or a malformed graph all return `null`, and [PreflightRouter]
 * degrades to standard Gemma tool-calling (PRD §3.2.1).
 */
class OnnxClassifierEngine(
    private val modelPath: File,
    private val logger: (String) -> Unit = {},
) : ClassifierEngine {

    private val mutex = Mutex()

    @Volatile private var session: OrtSession? = null
    @Volatile private var accelerator: ClassifierAccelerator? = null

    override val isLoaded: Boolean get() = session != null

    override suspend fun warmUp(): ClassifierAccelerator? = mutex.withLock {
        if (session != null) return@withLock accelerator
        withContext(Dispatchers.IO) {
            if (!modelPath.isFile) {
                logger("classifier model absent at $modelPath; agent falls through to Gemma")
                return@withContext null
            }
            try {
                val (ortSession, chosen) = createSession()
                verifySignature(ortSession)
                this@OnnxClassifierEngine.session = ortSession
                this@OnnxClassifierEngine.accelerator = chosen
                logger("classifier loaded on $chosen (model=${modelPath.name})")
                chosen
            } catch (t: Throwable) {
                logger("classifier warmUp failed; agent falls through to Gemma: ${t.message}")
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
        val s = session ?: return null
        return withContext(Dispatchers.IO) {
            try {
                runForward(s, inputIds, attentionMask)
            } catch (t: Throwable) {
                logger("classify failed: ${t.message}")
                null
            }
        }
    }

    override suspend fun unload(): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            session?.runCatching { close() }
            session = null
            accelerator = null
            // OrtEnvironment is a process-wide singleton; never closed here.
        }
    }

    // -- Internal helpers ---------------------------------------------------

    private fun cleanupOnFailure() {
        session?.runCatching { close() }
        session = null
        accelerator = null
    }

    /**
     * Try CUDA first, fall back to CPU on any error. `addCUDA` throws when the
     * CUDA EP native isn't compiled in (the default CPU-only `onnxruntime`
     * artifact) or no device is present — exactly the optional-GPU contract.
     */
    private fun createSession(): Pair<OrtSession, ClassifierAccelerator> {
        val env = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { gpuOpts ->
            try {
                gpuOpts.addCUDA(0)
                return env.createSession(modelPath.absolutePath, gpuOpts) to ClassifierAccelerator.GPU
            } catch (t: Throwable) {
                logger("CUDA EP unavailable; falling back to CPU (${t.message})")
            }
        }
        OrtSession.SessionOptions().use { cpuOpts ->
            return env.createSession(modelPath.absolutePath, cpuOpts) to ClassifierAccelerator.CPU
        }
    }

    /**
     * Assert the named inputs/outputs exist, then run a one-shot zeros pass.
     * [ClassifierOutput]'s `init` enforces head sizes `[3]`/`[2]`/`[6]`, so a
     * re-export that changes the signature fails at warmup, not silently mid-run.
     */
    private fun verifySignature(s: OrtSession) {
        require(INPUT_IDS in s.inputNames) { "missing input '$INPUT_IDS'; inputs=${s.inputNames}" }
        require(ATTENTION_MASK in s.inputNames) { "missing input '$ATTENTION_MASK'; inputs=${s.inputNames}" }
        for (name in OUTPUT_NAMES) {
            require(name in s.outputNames) { "missing output '$name'; outputs=${s.outputNames}" }
        }
        val zeros = LongArray(WordPieceTokenizer.MAX_SEQUENCE_LENGTH)
        runForward(s, zeros, zeros)
    }

    private fun runForward(
        s: OrtSession,
        inputIds: LongArray,
        attentionMask: LongArray,
    ): ClassifierOutput {
        val env = OrtEnvironment.getEnvironment()
        val idsTensor = OnnxTensor.createTensor(env, arrayOf(inputIds))
        val maskTensor = OnnxTensor.createTensor(env, arrayOf(attentionMask))
        try {
            s.run(mapOf(INPUT_IDS to idsTensor, ATTENTION_MASK to maskTensor)).use { result ->
                return ClassifierOutput(
                    preflightLogits = readVector(result, PREFLIGHT_OUTPUT),
                    presenceLogits = readVector(result, PRESENCE_OUTPUT),
                    categoryLogits = readVector(result, CATEGORY_OUTPUT),
                )
            }
        } finally {
            idsTensor.runCatching { close() }
            maskTensor.runCatching { close() }
        }
    }

    private fun readVector(result: OrtSession.Result, name: String): FloatArray {
        val value = result.get(name).orElseThrow {
            IllegalStateException("output '$name' absent at run; outputs=${result.map { it.key }}")
        }
        // Each head is shape [1, N] (batch 1) — float32. Take row 0.
        @Suppress("UNCHECKED_CAST")
        val matrix = (value as OnnxTensor).value as Array<FloatArray>
        return matrix[0]
    }

    companion object {
        const val INPUT_IDS: String = "input_ids"
        const val ATTENTION_MASK: String = "attention_mask"
        const val PREFLIGHT_OUTPUT: String = "preflight_logits"
        const val PRESENCE_OUTPUT: String = "presence_logits"
        const val CATEGORY_OUTPUT: String = "category_logits"

        private val OUTPUT_NAMES = listOf(PREFLIGHT_OUTPUT, PRESENCE_OUTPUT, CATEGORY_OUTPUT)
    }
}
