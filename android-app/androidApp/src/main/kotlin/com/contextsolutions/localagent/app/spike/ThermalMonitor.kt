package com.contextsolutions.localagent.app.spike

import android.content.Context
import android.os.PowerManager
import androidx.annotation.RequiresApi
import android.os.Build

/**
 * Thin wrapper over [PowerManager.OnThermalStatusChangedListener] that records the
 * peak thermal state observed during a benchmark run. Used by the spike harness to
 * answer the M0 question: does sustained Gemma 4 generation push Pixel 7 past
 * THERMAL_STATUS_MODERATE / SEVERE? (PRD section 4.3 specifies throttling at SEVERE
 * and refusing to run at CRITICAL.)
 */
class ThermalMonitor(context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var peakObserved: Int = PowerManager.THERMAL_STATUS_NONE
    private val listener = PowerManager.OnThermalStatusChangedListener { status ->
        if (status > peakObserved) peakObserved = status
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun start() {
        peakObserved = currentThermalStatus()
        powerManager.addThermalStatusListener(listener)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun stop() {
        powerManager.removeThermalStatusListener(listener)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun currentThermalStatus(): Int = powerManager.currentThermalStatus

    fun peakObservedThermalStatus(): Int = peakObserved
}
