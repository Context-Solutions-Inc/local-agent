package com.contextsolutions.localagent.inference

/**
 * Reads the OS-reported free RAM. Sibling to [ThermalStatusProvider] — both
 * sit in front of platform APIs the inference layer needs to gate model
 * activity on.
 *
 * Used by:
 *  - [com.contextsolutions.localagent.app.observability.MemoryPressureWatchdog]
 *    to proactively unload Gemma when free memory drops below ~1 GiB.
 *  - The chat send-time gate to refuse a fresh cold load when there isn't
 *    enough memory for it to succeed.
 *
 * The Android implementation wraps `ActivityManager.getMemoryInfo().availMem`,
 * which is a microsecond-level IPC into `system_server` — cheap enough to
 * call on the gate path. iOS will need an equivalent in Phase 2.
 */
fun interface MemoryHeadroomProvider {
    /** Snapshot of free system memory in bytes. */
    fun availableBytes(): Long
}
