package com.contextsolutions.mobileagent.inference

import java.lang.management.ManagementFactory

/**
 * Desktop [MemoryHeadroomProvider] (docs/DESKTOP_PORT_PLAN.md, Phase 7).
 *
 * The Android impl wraps `ActivityManager.availMem` (free system RAM). The JVM
 * equivalent is `com.sun.management.OperatingSystemMXBean.getFreePhysicalMemorySize()`
 * (renamed `getFreeMemorySize()` in JDK 19+; the toolchain is JDK 17 so the
 * older name applies, deprecated but present). That mirrors the Android
 * semantics — OS-reported free physical RAM, the number the warm-model gate
 * cares about — rather than JVM-heap headroom (which says nothing about whether
 * a multi-GB GGUF mmap will succeed).
 *
 * If the `com.sun.management` extension isn't available (a non-HotSpot JVM), it
 * falls back to the JVM heap's free+unallocated headroom via [Runtime], which
 * is at least a monotone "more is better" signal. Both paths are cheap enough
 * for the gate/watchdog cadence.
 */
class DesktopMemoryHeadroomProvider : MemoryHeadroomProvider {

    @Suppress("DEPRECATION") // getFreePhysicalMemorySize() — JDK 17; renamed only in 19+
    override fun availableBytes(): Long {
        val osBean = ManagementFactory.getOperatingSystemMXBean()
        if (osBean is com.sun.management.OperatingSystemMXBean) {
            val free = osBean.freePhysicalMemorySize
            if (free > 0L) return free
        }
        // Fallback: JVM heap headroom (free within the heap + still-unallocated
        // up to -Xmx). Used only on a JVM without the com.sun extension.
        val rt = Runtime.getRuntime()
        return rt.freeMemory() + (rt.maxMemory() - rt.totalMemory())
    }
}
