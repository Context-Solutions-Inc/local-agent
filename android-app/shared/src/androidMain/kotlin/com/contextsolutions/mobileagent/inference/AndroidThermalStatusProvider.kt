package com.contextsolutions.mobileagent.inference

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Android [ThermalStatusProvider]. Reads `PowerManager.currentThermalStatus`
 * for [current] and registers a `PowerManager.OnThermalStatusChangedListener`
 * for [statusFlow].
 *
 * The listener API is API 29+; minSdk = 36 (Android 16), so always present.
 */
class AndroidThermalStatusProvider(context: Context) : ThermalStatusProvider {

    private val powerManager: PowerManager =
        context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    override fun current(): ThermalStatus = mapAndroidStatus(powerManager.currentThermalStatus)

    override fun statusFlow(): Flow<ThermalStatus> = callbackFlow {
        // Emit the current value immediately so subscribers don't wait for
        // the first transition to know where we are. Phase B's caller treats
        // the absence of a value as "skip the eager load" defensively, so
        // surfacing the snapshot eagerly matters.
        trySend(mapAndroidStatus(powerManager.currentThermalStatus))

        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            trySend(mapAndroidStatus(status))
        }
        // `addThermalStatusListener` without an Executor posts callbacks on the
        // main thread. We're inside a Flow collected on whatever dispatcher the
        // caller chose, and the trySend is non-blocking, so main-thread delivery
        // is fine.
        powerManager.addThermalStatusListener(listener)
        awaitClose { powerManager.removeThermalStatusListener(listener) }
    }

    private fun mapAndroidStatus(raw: Int): ThermalStatus = when (raw) {
        PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.NONE
        PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
        PowerManager.THERMAL_STATUS_CRITICAL -> ThermalStatus.CRITICAL
        PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalStatus.EMERGENCY
        PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.SHUTDOWN
        else -> ThermalStatus.NONE // unknown future value — treat as no-throttle
    }
}
