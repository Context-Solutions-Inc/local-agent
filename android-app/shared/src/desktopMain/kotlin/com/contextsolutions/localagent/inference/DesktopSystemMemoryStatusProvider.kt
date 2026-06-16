package com.contextsolutions.localagent.inference

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Desktop [SystemMemoryStatusProvider] — a constant healthy state. The status
 * bands ([MemoryStatus]) map to the Pixel-7 LMKD memory-pressure thresholds
 * ([SystemMemoryThresholds]); there's no portable per-process desktop signal
 * that corresponds, so the header dot stays green (mirrors
 * `DesktopThermalStatusProvider` always reporting NONE, Phase 7 inc 1).
 */
class DesktopSystemMemoryStatusProvider : SystemMemoryStatusProvider {
    override val status: StateFlow<MemoryStatus> = MutableStateFlow(MemoryStatus.Green)
}
