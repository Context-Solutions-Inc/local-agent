package com.contextsolutions.localagent.app.service

import com.contextsolutions.localagent.inference.ThermalStatusProvider
import com.contextsolutions.localagent.memory.EmbedderAccelerator
import com.contextsolutions.localagent.memory.EmbedderEngine
import com.contextsolutions.localagent.memory.EmbedderOutput
import com.contextsolutions.localagent.telemetry.CounterNames
import com.contextsolutions.localagent.telemetry.NoOpTelemetryCounters
import com.contextsolutions.localagent.telemetry.TelemetryCounters

/**
 * [EmbedderEngine] wrapper analogous to [ManagedClassifierEngine].
 * Adds the Gemma-style lifecycle on top of the raw
 * [com.contextsolutions.localagent.memory.LiteRtEmbedderEngine] — 5-min
 * idle unload, eager warm-up, `onTrimMemory` + watchdog `forceUnload`.
 * Call sites (`MemoryRetriever`, `MemoryExtractor`) see the
 * [EmbedderEngine] interface unchanged.
 */
internal class ManagedEmbedderEngine(
    private val delegate: EmbedderEngine,
    thermalStatusProvider: ThermalStatusProvider,
    counters: TelemetryCounters = NoOpTelemetryCounters,
) : EmbedderEngine {

    private val session = AuxModelSession(
        name = "embedder",
        warmUpDelegate = { delegate.warmUp() != null },
        unloadDelegate = { delegate.unload() },
        thermalStatusProvider = thermalStatusProvider,
        counters = counters,
        counterNames = AuxModelSession.CounterNames(
            warmupLoadedTotal = CounterNames.EMBEDDER_WARMUP_LOADED_TOTAL,
            warmupAlreadyLoadedTotal = CounterNames.EMBEDDER_WARMUP_ALREADY_LOADED_TOTAL,
            warmupSkippedThermalTotal = CounterNames.EMBEDDER_WARMUP_SKIPPED_THERMAL_TOTAL,
            warmupFailedTotal = CounterNames.EMBEDDER_WARMUP_FAILED_TOTAL,
            unloadedIdleTotal = CounterNames.EMBEDDER_UNLOADED_IDLE_TOTAL,
            unloadedTrimMemoryTotal = CounterNames.EMBEDDER_UNLOADED_TRIM_MEMORY_TOTAL,
            unloadedWatchdogTotal = CounterNames.EMBEDDER_UNLOADED_WATCHDOG_TOTAL,
        ),
    )

    override val isLoaded: Boolean get() = session.isLoaded

    override suspend fun warmUp(): EmbedderAccelerator? =
        when (session.warmUpIfPossible()) {
            AuxWarmUpOutcome.AlreadyLoaded, AuxWarmUpOutcome.Loaded -> EmbedderAccelerator.CPU
            is AuxWarmUpOutcome.SkippedThermal, is AuxWarmUpOutcome.Failed -> null
        }

    override suspend fun embed(text: String): EmbedderOutput? =
        session.withSession { delegate.embed(text) }

    override suspend fun unload() {
        session.forceUnload(UnloadReason.Manual)
    }

    fun forceUnload(reason: UnloadReason) {
        session.forceUnload(reason)
    }
}
