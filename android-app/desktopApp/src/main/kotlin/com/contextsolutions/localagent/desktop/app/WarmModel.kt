package com.contextsolutions.localagent.desktop.app

import com.contextsolutions.localagent.agent.InferenceSession
import com.contextsolutions.localagent.inference.Accelerator
import com.contextsolutions.localagent.inference.DesktopModelInventory
import com.contextsolutions.localagent.inference.GenerationEvent
import com.contextsolutions.localagent.inference.GenerationRequest
import com.contextsolutions.localagent.inference.InferenceConfig
import com.contextsolutions.localagent.inference.InferenceEngine
import com.contextsolutions.localagent.inference.ModelHandle
import com.contextsolutions.localagent.inference.ToolDispatcher
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Holds the GGUF model resident for the lifetime of the desktop app
 * (docs/DESKTOP_PORT_PLAN.md, Phase 7 — system tray + warm-model background).
 *
 * Unlike Android — where `InferenceForegroundService` + the main-thread watchdog
 * gate model residency (invariants #1/#29) — desktop has no foreground-service
 * constraint, so the handle simply stays loaded once warmed and survives the
 * window being hidden to the tray. In-flight `generate()` keeps running on the
 * long-lived app scope (the [TaskQueue]'s scope), so closing the window to the
 * tray never interrupts a background turn.
 *
 * The model loads lazily on first [session] (a queued task or chat turn). [Main]
 * additionally kicks an eager warm at startup (via [DesktopChatSessionController.
 * warmUp], gated on [isModelPresent]) so the first chat turn doesn't pay the
 * multi-second cold-load; the load itself still funnels through [ensureLoaded]
 * here. `GEMMA_GGUF_PATH` overrides the inventory path for dev, the same env the
 * headless harness uses; otherwise the verified download location
 * ([DesktopModelInventory.localFile]) is used.
 */
class WarmModel(
    private val engine: InferenceEngine,
    private val inventory: DesktopModelInventory,
    private val enableVision: Boolean = false,
    private val logger: (String) -> Unit = { System.err.println("[WarmModel] $it") },
) {
    private val mutex = Mutex()

    @Volatile
    private var handle: ModelHandle? = null

    /**
     * The accelerator the resident handle reports, or `null` until first load.
     * Best-effort — `LlamaCppInferenceEngine` derives it from the requested
     * GPU-layer count, not a real probe: a GPU-capable native + AUTO/GPU config
     * reports [Accelerator.GPU], the CPU-only artifact CPU. Read by
     * [DesktopChatSessionController] for the session banner.
     */
    val activeAccelerator: Accelerator?
        get() = handle?.activeAccelerator

    /** An [InferenceSession] over the resident handle, loading it on first call. */
    suspend fun session(): InferenceSession {
        val loaded = ensureLoaded()
        return object : InferenceSession {
            override fun generate(
                request: GenerationRequest,
                toolDispatcher: ToolDispatcher?,
            ): Flow<GenerationEvent> = engine.generate(loaded, request, toolDispatcher)
        }
    }

    /**
     * Whether the GGUF the next load will read is already on disk. [Main] gates
     * the startup warm-up on this so a fresh-install background download isn't
     * pre-empted by a load that would just fail.
     */
    fun isModelPresent(): Boolean = handle != null || resolveModelPath().isFile

    /** `GEMMA_GGUF_PATH` dev override, else the verified download location. */
    private fun resolveModelPath(): File =
        System.getenv("GEMMA_GGUF_PATH")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: inventory.localFile()

    private suspend fun ensureLoaded(): ModelHandle = mutex.withLock {
        handle ?: run {
            val path = resolveModelPath().absolutePath
            logger("loading $path")
            engine.loadModel(path, InferenceConfig(enableVision = enableVision)).also {
                handle = it
                logger("loaded on ${it.activeAccelerator}")
            }
        }
    }

    /** Release the model (app quit). Safe if never loaded. */
    fun unload() {
        handle?.let {
            engine.unload(it)
            handle = null
            logger("unloaded")
        }
    }

    /**
     * Drop the resident handle so the next [session] re-loads from scratch
     * (PR #56). Used when the Ollama server config changes: the routing engine
     * re-decides remote-vs-local on the next [ensureLoaded]. Safe if never
     * loaded; an in-flight `generate()` already captured its handle and finishes
     * uninterrupted.
     */
    fun invalidate() {
        handle?.let {
            engine.unload(it)
            handle = null
            logger("invalidated (config changed)")
        }
    }
}
