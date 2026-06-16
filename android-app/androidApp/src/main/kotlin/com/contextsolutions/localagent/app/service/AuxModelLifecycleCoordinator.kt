package com.contextsolutions.localagent.app.service

import com.contextsolutions.localagent.classifier.ClassifierEngine
import com.contextsolutions.localagent.memory.EmbedderEngine

/**
 * Single fan-out handle for aux-model lifecycle commands. Lets the
 * `Application.onTrimMemory`, `MainThreadHeartbeatWatchdog`, and
 * `MainViewModel.warmUpAuxEngines` call sites pass through one dependency
 * instead of holding separate references to the classifier and
 * embedder engines.
 *
 * The constructor takes the engine singletons; Koin's `androidModule` resolves
 * them to the [ManagedClassifierEngine] / [ManagedEmbedderEngine]
 * instances. The
 * coordinator type-checks the binding is the managed flavor at runtime
 * and degrades to a no-op for the raw engines so test wiring (which
 * may inject the raw [com.contextsolutions.localagent.classifier.LiteRtClassifierEngine]
 * directly) doesn't crash.
 */
class AuxModelLifecycleCoordinator(
    private val classifierEngine: ClassifierEngine,
    private val embedderEngine: EmbedderEngine,
) {

    /**
     * Eager warm-up. Fans out to both engines in parallel-friendly
     * fashion (the caller is responsible for the dispatcher; we just
     * call through). Idempotent — re-entering chat from settings or
     * coming back from background re-fires this but already-loaded
     * engines return immediately.
     */
    suspend fun warmUpAll() {
        classifierEngine.warmUp()
        embedderEngine.warmUp()
    }

    /**
     * Drop both engines' native heap. Fired from:
     *  - `LocalAgentApplication.onTrimMemory(CRITICAL+)`
     *  - `MainThreadHeartbeatWatchdog.trip` (GPU-saturation mitigation)
     *
     * Reason determines which telemetry counter increments. Defers if a
     * classify/embed is in flight — see [AuxModelSession.forceUnload].
     */
    fun forceUnload(reason: UnloadReason) {
        (classifierEngine as? ManagedClassifierEngine)?.forceUnload(reason)
        (embedderEngine as? ManagedEmbedderEngine)?.forceUnload(reason)
    }
}
