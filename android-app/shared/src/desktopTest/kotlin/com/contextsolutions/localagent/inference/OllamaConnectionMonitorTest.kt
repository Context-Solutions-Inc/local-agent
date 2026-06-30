package com.contextsolutions.localagent.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * PR #56 — the offline→online recovery handshake. Verifies the monitor asks for
 * a reload immediately on failure (fall back to local) and again once the
 * background probe sees the server return (reconnect).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OllamaConnectionMonitorTest {

    @Test
    fun fallsBackImmediatelyThenReconnectsWhenServerReturns() = runTest {
        var healthy = false
        val monitor = OllamaConnectionMonitor(
            healthProbe = { healthy },
            scope = backgroundScope,
            probeIntervalMs = 1_000,
        )
        val reloads = mutableListOf<Unit>()
        backgroundScope.launch { monitor.reloadRequests.collect { reloads.add(Unit) } }
        runCurrent()

        // Server lost mid-session → immediate reload (fall back to local).
        monitor.onRemoteUnreachable("http://host:11434")
        runCurrent()
        assertEquals(1, reloads.size)

        // Still down after a probe interval → no reconnect yet.
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(1, reloads.size)

        // Server returns → next probe requests a reload (reconnect to Ollama).
        healthy = true
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(2, reloads.size)
    }

    @Test
    fun healthyStopsTheReconnectWatch() = runTest {
        var probes = 0
        val monitor = OllamaConnectionMonitor(
            healthProbe = { probes++; false },
            scope = backgroundScope,
            probeIntervalMs = 1_000,
        )
        backgroundScope.launch { monitor.reloadRequests.collect { } }
        runCurrent()

        monitor.beginReconnectWatch("http://host:11434")
        runCurrent()
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(1, probes)

        // A successful connect elsewhere cancels the watch → no further probes.
        monitor.onRemoteHealthy()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, probes)
    }
}
