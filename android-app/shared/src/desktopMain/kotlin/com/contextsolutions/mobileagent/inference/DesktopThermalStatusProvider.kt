package com.contextsolutions.mobileagent.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Desktop [ThermalStatusProvider] (docs/DESKTOP_PORT_PLAN.md, Phase 7).
 *
 * Desktops/laptops expose no portable, OS-agnostic thermal-throttle API the way
 * Android's `PowerManager.currentThermalStatus` does (invariant #4 — even on
 * Android the high-level API lags the real throttle). So the desktop provider
 * is a deliberate stub: it always reports [ThermalStatus.NONE] (the no-throttle,
 * "nominal" state), so none of the thermal gates (`isThrottling`/`isBlocking`)
 * ever fire on desktop. The warm-model background path (Phase 7 tray) therefore
 * never skips a load or blocks generation on thermal grounds — appropriate for
 * a machine on mains power without the 8 GB-Pixel-7 LMKD pressure the gates
 * were built for.
 *
 * If a real signal is ever wanted, the place to add it is per-OS sysfs / WMI /
 * IOKit probing behind this same interface; until then NONE is the honest
 * answer (better than fabricating a FAIR/SEVERE the UI would surface as a
 * spurious banner).
 */
class DesktopThermalStatusProvider : ThermalStatusProvider {

    override fun current(): ThermalStatus = ThermalStatus.NONE

    // Single immutable emission: desktop thermal state never transitions, so a
    // one-shot flow matches the contract ("emit current on subscribe") without
    // holding a listener open.
    override fun statusFlow(): Flow<ThermalStatus> = flowOf(ThermalStatus.NONE)
}
