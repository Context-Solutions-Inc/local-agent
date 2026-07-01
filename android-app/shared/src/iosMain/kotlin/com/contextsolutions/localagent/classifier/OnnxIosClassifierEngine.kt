package com.contextsolutions.localagent.classifier

import com.contextsolutions.localagent.inference.NativeClassifierBridge
import kotlin.concurrent.Volatile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * iOS [ClassifierEngine] adapter (Phase 2). Wraps the Swift [NativeClassifierBridge]
 * (ONNX Runtime, CoreML/CPU) into the shared engine seam, the counterpart of Android
 * `LiteRtClassifierEngine` and desktop `OnnxClassifierEngine`.
 *
 * The bridge is pure-numeric: this adapter passes the already-tokenized
 * `input_ids`/`attention_mask` straight through (`PreflightRouter`/`MemoryExtractor`
 * own the [WordPieceTokenizer]) and reconstructs [ClassifierOutput] from the three
 * returned head vectors — whose `init` enforces the `[3]`/`[2]`/`[6]` sizes, so a
 * re-export that drifts the signature returns `null` instead of corrupting routing.
 *
 * **Never throws** (the engine contract): a missing model, a load failure, a
 * wrong-length input, or a size-drifted output all resolve to `null` and
 * `PreflightRouter` degrades to Gemma tool-calling. [modelPath] is download-gated —
 * it returns `null` until the verified `.onnx` is on disk, so [warmUp] no-ops (and
 * the model can be fetched lazily on first feature use).
 */
class OnnxIosClassifierEngine(
    private val bridge: NativeClassifierBridge,
    private val modelPath: () -> String?,
    private val logger: (String) -> Unit = {},
) : ClassifierEngine {

    private val mutex = Mutex()

    @Volatile private var loaded = false
    @Volatile private var accelerator: ClassifierAccelerator? = null

    override val isLoaded: Boolean get() = loaded

    override suspend fun warmUp(): ClassifierAccelerator? = mutex.withLock {
        if (loaded) return accelerator
        val path = modelPath() ?: run {
            logger("classifier model absent; agent falls through to Gemma")
            return null
        }
        suspendCancellableCoroutine { cont ->
            bridge.load(
                modelPath = path,
                useGpu = true,
                onLoaded = { accel ->
                    loaded = true
                    accelerator =
                        if (accel.equals("gpu", ignoreCase = true)) ClassifierAccelerator.GPU
                        else ClassifierAccelerator.CPU
                    logger("classifier loaded on $accelerator")
                    if (cont.isActive) cont.resume(accelerator)
                },
                onError = { msg ->
                    logger("classifier warmUp failed; agent falls through to Gemma: $msg")
                    if (cont.isActive) cont.resume(null)
                },
            )
        }
    }

    override suspend fun classify(inputIds: LongArray, attentionMask: LongArray): ClassifierOutput? {
        if (!loaded) return null
        // Guard-return null (never throw) — diverges from desktop's require().
        if (inputIds.size != WordPieceTokenizer.MAX_SEQUENCE_LENGTH ||
            attentionMask.size != WordPieceTokenizer.MAX_SEQUENCE_LENGTH
        ) {
            logger("classify skipped: bad input length ids=${inputIds.size} mask=${attentionMask.size}")
            return null
        }
        return suspendCancellableCoroutine { cont ->
            bridge.classify(
                inputIds = inputIds,
                attentionMask = attentionMask,
                onResult = { pre, pres, cat ->
                    val out = try {
                        ClassifierOutput(preflightLogits = pre, presenceLogits = pres, categoryLogits = cat)
                    } catch (t: Throwable) {
                        logger("classify output drift: ${t.message}")
                        null
                    }
                    if (cont.isActive) cont.resume(out)
                },
                onError = { msg ->
                    logger("classify failed: $msg")
                    if (cont.isActive) cont.resume(null)
                },
            )
        }
    }

    override suspend fun unload(): Unit = mutex.withLock {
        loaded = false
        accelerator = null
        bridge.unload()
    }
}
