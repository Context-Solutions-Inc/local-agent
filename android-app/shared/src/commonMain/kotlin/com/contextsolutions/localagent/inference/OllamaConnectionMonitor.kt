package com.contextsolutions.localagent.inference

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Recovers gracefully when a remote Ollama server (PR #56) drops offline and
 * later returns, so the user doesn't get stuck on either backend.
 *
 * The backend choice is made once per model load ([RoutingInferenceEngine]) and
 * cached in the resident handle, so a mid-session outage would otherwise keep
 * erroring against the dead server, and a recovery would otherwise leave the app
 * pinned to the local fallback until a manual reload. This monitor closes both
 * gaps by asking the session owner to drop the resident handle (so the *next*
 * turn re-runs the routing decision) at the right moments:
 *
 *  - **Server lost** ([onRemoteUnreachable], fired by [OllamaInferenceEngine]
 *    when a turn can't reach the server): request a reload immediately → the
 *    next turn re-probes, finds it down, and falls back to the on-device model.
 *    A background watch then polls the server.
 *  - **Server back** (the watch's [healthProbe] succeeds): request a reload →
 *    the next turn re-probes, finds it up, and reconnects to Ollama.
 *  - **Server healthy** ([onRemoteHealthy], fired on a successful connect): stop
 *    the watch.
 *
 * The session owner (Android `InferenceSessionManager`, desktop `WarmModel`)
 * collects [reloadRequests] and drops its handle — the same lever the Settings
 * config-change observer already uses.
 */
class OllamaConnectionMonitor(
    /** Probes server reachability; in production `OllamaClient.health`. */
    private val healthProbe: suspend (baseUrl: String) -> Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val probeIntervalMs: Long = DEFAULT_PROBE_INTERVAL_MS,
    private val logger: (String) -> Unit = {},
) {
    private val _reloadRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** Drop the resident model handle so the next turn re-decides the backend. */
    val reloadRequests: SharedFlow<Unit> = _reloadRequests.asSharedFlow()

    private val mutex = Mutex()
    private var watchJob: Job? = null
    private var watchedUrl: String? = null

    /**
     * A turn couldn't reach the server: fall back to local now (request a
     * reload), then watch for the server's return.
     */
    fun onRemoteUnreachable(baseUrl: String) {
        _reloadRequests.tryEmit(Unit)
        scope.launch { startWatch(baseUrl) }
    }

    /**
     * The server was unreachable at load time (the router is already falling back
     * to local): just start watching for its return — no reload needed.
     */
    fun beginReconnectWatch(baseUrl: String) {
        scope.launch { startWatch(baseUrl) }
    }

    /** The server is responding: stop any pending reconnect watch. */
    fun onRemoteHealthy() {
        scope.launch { stopWatch() }
    }

    /**
     * Drop the resident handle so the next turn re-decides the backend. Used by the
     * relay transport provider when the relay pipe comes up/down (the relay has no
     * pollable health URL, so it pushes the reload instead of being watched).
     */
    fun requestReload() {
        _reloadRequests.tryEmit(Unit)
    }

    private suspend fun startWatch(baseUrl: String) = mutex.withLock {
        if (watchJob?.isActive == true && watchedUrl == baseUrl) return
        watchJob?.cancel()
        watchedUrl = baseUrl
        watchJob = scope.launch {
            logger("watching $baseUrl for return")
            while (isActive) {
                delay(probeIntervalMs)
                if (healthProbe(baseUrl)) {
                    logger("$baseUrl back online — requesting reconnect")
                    _reloadRequests.tryEmit(Unit)
                    break
                }
            }
        }
    }

    private suspend fun stopWatch() = mutex.withLock {
        watchJob?.cancel()
        watchJob = null
        watchedUrl = null
    }

    private companion object {
        const val DEFAULT_PROBE_INTERVAL_MS = 15_000L
    }
}
