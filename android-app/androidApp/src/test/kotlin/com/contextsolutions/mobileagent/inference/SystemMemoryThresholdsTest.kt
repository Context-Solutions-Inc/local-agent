package com.contextsolutions.mobileagent.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * PR #18 — boundary behaviour of [SystemMemoryThresholds.classify] and the
 * `init` invariants that protect the three bands from being ordered wrong.
 */
class SystemMemoryThresholdsTest {

    private val thresholds = SystemMemoryThresholds.DEFAULT

    @Test
    fun `classify is Red just below the watchdog floor`() {
        val justBelow = thresholds.watchdogUnloadBytes - 1
        assertEquals(MemoryStatus.Red, thresholds.classify(justBelow))
    }

    @Test
    fun `classify is Yellow exactly at the watchdog floor`() {
        assertEquals(MemoryStatus.Yellow, thresholds.classify(thresholds.watchdogUnloadBytes))
    }

    @Test
    fun `classify is Yellow just below the cold-load floor`() {
        assertEquals(MemoryStatus.Yellow, thresholds.classify(thresholds.coldLoadMinBytes - 1))
    }

    @Test
    fun `classify is Green exactly at the cold-load floor`() {
        assertEquals(MemoryStatus.Green, thresholds.classify(thresholds.coldLoadMinBytes))
    }

    @Test
    fun `classify is Green well above the cold-load floor`() {
        assertEquals(MemoryStatus.Green, thresholds.classify(thresholds.coldLoadMinBytes * 4))
    }

    @Test
    fun `rejects non-positive watchdog floor`() {
        assertThrows(IllegalArgumentException::class.java) {
            SystemMemoryThresholds(
                watchdogUnloadBytes = 0,
                hotPathMinBytes = 1,
                coldLoadMinBytes = 2,
            )
        }
    }

    @Test
    fun `rejects hot-path below watchdog floor`() {
        assertThrows(IllegalArgumentException::class.java) {
            SystemMemoryThresholds(
                watchdogUnloadBytes = 1_000,
                hotPathMinBytes = 500,
                coldLoadMinBytes = 2_000,
            )
        }
    }

    @Test
    fun `rejects cold-load below hot-path`() {
        assertThrows(IllegalArgumentException::class.java) {
            SystemMemoryThresholds(
                watchdogUnloadBytes = 100,
                hotPathMinBytes = 1_000,
                coldLoadMinBytes = 500,
            )
        }
    }
}
