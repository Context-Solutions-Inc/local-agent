package com.contextsolutions.mobileagent.app.service

import com.contextsolutions.mobileagent.classifier.ClassifierAccelerator
import com.contextsolutions.mobileagent.classifier.ClassifierEngine
import com.contextsolutions.mobileagent.classifier.ClassifierOutput
import com.contextsolutions.mobileagent.inference.ThermalStatusProvider
import com.contextsolutions.mobileagent.telemetry.CounterNames
import com.contextsolutions.mobileagent.telemetry.NoOpTelemetryCounters
import com.contextsolutions.mobileagent.telemetry.TelemetryCounters

/**
 * [ClassifierEngine] wrapper that adds the Gemma-style lifecycle
 * (5-min idle unload, eager warm-up, `onTrimMemory` + watchdog
 * `forceUnload`) on top of the raw [com.contextsolutions.mobileagent.classifier.LiteRtClassifierEngine].
 *
 * Call sites (`PreflightRouter`, `MemoryExtractor`) are unchanged —
 * they see a [ClassifierEngine] just like before. The lifetime
 * decisions live in [AuxModelSession].
 *
 * PR #8 / mirrors Gemma's [InferenceSessionManager]. See the parent
 * class for the full state-machine documentation.
 */
internal class ManagedClassifierEngine(
    private val delegate: ClassifierEngine,
    thermalStatusProvider: ThermalStatusProvider,
    counters: TelemetryCounters = NoOpTelemetryCounters,
) : ClassifierEngine {

    private val session = AuxModelSession(
        name = "classifier",
        warmUpDelegate = { delegate.warmUp() != null },
        unloadDelegate = { delegate.unload() },
        thermalStatusProvider = thermalStatusProvider,
        counters = counters,
        counterNames = AuxModelSession.CounterNames(
            warmupLoadedTotal = CounterNames.CLASSIFIER_WARMUP_LOADED_TOTAL,
            warmupAlreadyLoadedTotal = CounterNames.CLASSIFIER_WARMUP_ALREADY_LOADED_TOTAL,
            warmupSkippedThermalTotal = CounterNames.CLASSIFIER_WARMUP_SKIPPED_THERMAL_TOTAL,
            warmupFailedTotal = CounterNames.CLASSIFIER_WARMUP_FAILED_TOTAL,
            unloadedIdleTotal = CounterNames.CLASSIFIER_UNLOADED_IDLE_TOTAL,
            unloadedTrimMemoryTotal = CounterNames.CLASSIFIER_UNLOADED_TRIM_MEMORY_TOTAL,
            unloadedWatchdogTotal = CounterNames.CLASSIFIER_UNLOADED_WATCHDOG_TOTAL,
        ),
    )

    override val isLoaded: Boolean get() = session.isLoaded

    /**
     * Eager warm-up. The [AuxWarmUpOutcome] is collapsed to the
     * [ClassifierEngine.warmUp] return contract: non-null on success,
     * null on skip/failure. Aux models don't surface their accelerator
     * to UI so the original [ClassifierAccelerator] value isn't
     * threaded through — the underlying engine logs it on load anyway.
     */
    override suspend fun warmUp(): ClassifierAccelerator? =
        when (session.warmUpIfPossible()) {
            AuxWarmUpOutcome.AlreadyLoaded, AuxWarmUpOutcome.Loaded -> ClassifierAccelerator.CPU
            is AuxWarmUpOutcome.SkippedThermal, is AuxWarmUpOutcome.Failed -> null
        }

    override suspend fun classify(
        inputIds: LongArray,
        attentionMask: LongArray,
    ): ClassifierOutput? =
        session.withSession { delegate.classify(inputIds, attentionMask) }

    override suspend fun unload() {
        session.forceUnload(UnloadReason.Manual)
    }

    /**
     * Reason-tagged variant used by [MobileAgentApplication.onTrimMemory] and
     * [MainThreadHeartbeatWatchdog] so the right unload counter
     * increments. Not on the [ClassifierEngine] interface because only
     * the Android lifecycle plumbing has a meaningful reason to pass.
     */
    fun forceUnload(reason: UnloadReason) {
        session.forceUnload(reason)
    }
}
