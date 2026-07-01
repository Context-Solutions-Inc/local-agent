package com.contextsolutions.localagent.inference

import com.contextsolutions.localagent.classifier.ClassifierEngine
import com.contextsolutions.localagent.memory.EmbedderEngine
import com.contextsolutions.localagent.memory.MemoryPreferences
import com.contextsolutions.localagent.platform.SecureStorage
import com.contextsolutions.localagent.platform.SecureStorageKeys
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Lazily downloads + warms the two aux `.onnx` models on iOS (Phase 2), the iOS
 * counterpart of Android's `AuxModelLifecycleCoordinator` / desktop's startup warm —
 * but far simpler (no thermal/trim machinery, no foreground service).
 *
 * **Feature-gated + lazy** so first run stays the LLM-only ~2.58 GB experience and the
 * aux models (357 MB total) are fetched only when the feature that needs them is on:
 *  - **Classifier** (266 MB) — gated on `SEARCH_ENABLED` (default OFF, invariant #73),
 *    so it stays dormant until the user turns on web search. Without it `PreflightRouter`
 *    falls through to Gemma.
 *  - **Embedder** (91 MB) — gated on [MemoryPreferences.creationEnabled] (default ON,
 *    PRD §3.2.4), so memory works out of the box once the small model lands. Without it
 *    the memory subsystem degrades to no-op.
 *
 * [kickIfEnabled] is idempotent (a per-model in-flight/done guard) and swallows failures
 * — a network error just leaves `warmUp()` returning null and the feature degraded; the
 * resumable [IosAuxModelStore] recovers on the next kick. Toggling a feature on takes
 * effect on the next [kickIfEnabled] (the iOS entry point kicks it when the main UI shows).
 */
class IosAuxModelWarmer(
    private val store: IosAuxModelStore,
    private val classifier: ClassifierEngine,
    private val embedder: EmbedderEngine,
    private val secureStorage: SecureStorage,
    private val memoryPreferences: MemoryPreferences,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {},
) {

    @Volatile private var classifierStarted = false
    @Volatile private var embedderStarted = false

    private fun searchEnabled(): Boolean =
        secureStorage.get(SecureStorageKeys.SEARCH_ENABLED) == "true"

    /** Ensure + warm each aux model whose feature is currently enabled. Safe to call repeatedly. */
    fun kickIfEnabled() {
        if (!classifierStarted && searchEnabled()) {
            classifierStarted = true
            scope.launch {
                runCatching {
                    withDownloadClient { store.ensureClassifier(it) { } }
                    classifier.warmUp()
                }.onFailure {
                    logger("classifier ensure/warm failed: ${it.message}")
                    classifierStarted = false // allow a later retry
                }
            }
        }
        if (!embedderStarted && memoryPreferences.creationEnabled()) {
            embedderStarted = true
            scope.launch {
                runCatching {
                    withDownloadClient { store.ensureEmbedder(it) { } }
                    embedder.warmUp()
                }.onFailure {
                    logger("embedder ensure/warm failed: ${it.message}")
                    embedderStarted = false
                }
            }
        }
    }

    // A dedicated Darwin client with NO request timeout — the shared HttpEngineFactory
    // caps requests at 10s, which would abort a multi-hundred-MB fetch (mirrors
    // IosModelDownloadController).
    private suspend inline fun withDownloadClient(block: (HttpClient) -> Unit) {
        val client = HttpClient(Darwin)
        try {
            block(client)
        } finally {
            client.close()
        }
    }
}
