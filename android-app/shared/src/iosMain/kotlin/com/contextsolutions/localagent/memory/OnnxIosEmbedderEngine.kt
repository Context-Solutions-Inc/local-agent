package com.contextsolutions.localagent.memory

import com.contextsolutions.localagent.classifier.WordPieceTokenizer
import com.contextsolutions.localagent.inference.NativeEmbedderBridge
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * iOS [EmbedderEngine] adapter (Phase 2). Wraps the Swift [NativeEmbedderBridge]
 * (ONNX Runtime, CoreML/CPU) into the shared engine seam, the counterpart of Android
 * `LiteRtEmbedderEngine` and desktop `OnnxEmbedderEngine`.
 *
 * The `EmbedderEngine.embed(text)` contract takes raw text, so — unlike the classifier
 * adapter — this engine **tokenizes internally** via the injected [WordPieceTokenizer]
 * (`encodeSingle`, exactly like the desktop engine), because the bridge is numeric-only.
 * The returned vector is validated by [EmbedderOutput]'s `init` (384-dim), so a
 * re-export with a different hidden size returns `null` instead of corrupting the
 * memory BLOB column.
 *
 * **Never throws**: a missing model, a load failure, a tokenizer error, or a
 * size-drifted output all resolve to `null` and the memory subsystem degrades to
 * no-op. [modelPath] is download-gated (lazy fetch on first memory use).
 */
class OnnxIosEmbedderEngine(
    private val bridge: NativeEmbedderBridge,
    private val tokenizer: WordPieceTokenizer,
    private val modelPath: () -> String?,
    private val logger: (String) -> Unit = {},
) : EmbedderEngine {

    private val mutex = Mutex()

    @Volatile private var loaded = false
    @Volatile private var accelerator: EmbedderAccelerator? = null

    override val isLoaded: Boolean get() = loaded

    override suspend fun warmUp(): EmbedderAccelerator? = mutex.withLock {
        if (loaded) return accelerator
        val path = modelPath() ?: run {
            logger("embedder model absent; memory subsystem degrades to no-op")
            return null
        }
        suspendCancellableCoroutine { cont ->
            bridge.load(
                modelPath = path,
                useGpu = true,
                onLoaded = { accel ->
                    loaded = true
                    accelerator =
                        if (accel.equals("gpu", ignoreCase = true)) EmbedderAccelerator.GPU
                        else EmbedderAccelerator.CPU
                    logger("embedder loaded on $accelerator")
                    if (cont.isActive) cont.resume(accelerator)
                },
                onError = { msg ->
                    logger("embedder warmUp failed; memory subsystem degrades to no-op: $msg")
                    if (cont.isActive) cont.resume(null)
                },
            )
        }
    }

    override suspend fun embed(text: String): EmbedderOutput? {
        if (!loaded) return null
        val tokenized = try {
            withContext(Dispatchers.Default) { tokenizer.encodeSingle(text) }
        } catch (t: Throwable) {
            logger("tokenize failed for text length=${text.length}: ${t.message}")
            return null
        }
        return suspendCancellableCoroutine { cont ->
            bridge.embed(
                inputIds = tokenized.inputIds,
                attentionMask = tokenized.attentionMask,
                onResult = { vec ->
                    val out = try {
                        EmbedderOutput(vec)
                    } catch (t: Throwable) {
                        logger("embed output drift: ${t.message}")
                        null
                    }
                    if (cont.isActive) cont.resume(out)
                },
                onError = { msg ->
                    logger("embed failed: $msg")
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
