package com.contextsolutions.mobileagent.inference

import kotlinx.coroutines.flow.Flow

/**
 * Platform-abstracted thermal state per PRD §4.3.
 *
 * Values mirror Android's `PowerManager.THERMAL_STATUS_*` ordinals so an
 * Android caller can map back-and-forth without translation. iOS uses
 * `NSProcessInfo.thermalState` which is coarser (NOMINAL/FAIR/SERIOUS/CRITICAL);
 * the iOS actual maps onto the closest entries here when Phase 2 ports the
 * embedder + memory subsystem.
 *
 * The two trailing Android-only values ([EMERGENCY], [SHUTDOWN]) are listed
 * for completeness; consumers should treat anything ≥ [SEVERE] as "throttle
 * aggressively" and ≥ [CRITICAL] as "refuse new work".
 */
enum class ThermalStatus(val ordinal0: Int) {
    NONE(0),
    LIGHT(1),
    MODERATE(2),
    SEVERE(3),
    CRITICAL(4),
    EMERGENCY(5),
    SHUTDOWN(6);

    /**
     * True at SEVERE or higher — the threshold at which M6 Phase B skips the
     * eager Gemma load (the device is already throttling; further heavy work
     * would worsen the user experience).
     */
    val isThrottling: Boolean get() = this >= SEVERE

    /**
     * True at CRITICAL or higher — Phase E shows a full-screen block and
     * disables the send button. PRD §4.3 says users must be "warned" in
     * critical; we interpret that as blocking new generation rather than
     * letting the OS kill the app mid-response.
     */
    val isBlocking: Boolean get() = this >= CRITICAL
}

/**
 * Read the device's current thermal state. Phase B (eager Gemma load) calls
 * [current] before invoking the model load. Phase E surfaces thermal state
 * to the chat UI via [statusFlow] (banner at SEVERE, block at CRITICAL).
 */
interface ThermalStatusProvider {
    /** Snapshot the current thermal state. Cheap; safe to call from any dispatcher. */
    fun current(): ThermalStatus

    /**
     * Stream of thermal state changes. Emits the current value on subscribe
     * and then each subsequent transition. Backed by
     * `PowerManager.addThermalStatusListener` on Android. The cold start of
     * each subscription is cheap (no listener registered until first collect).
     */
    fun statusFlow(): Flow<ThermalStatus>
}
