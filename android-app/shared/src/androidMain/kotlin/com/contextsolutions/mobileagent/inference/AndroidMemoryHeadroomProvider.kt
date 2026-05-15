package com.contextsolutions.mobileagent.inference

import android.app.ActivityManager
import android.content.Context

/**
 * Android [MemoryHeadroomProvider]. Wraps
 * `ActivityManager.getMemoryInfo().availMem` — the OS's authoritative
 * snapshot of "free RAM available to start new processes / allocations".
 *
 * The call is a microsecond-level IPC into `system_server`. Cheap enough to
 * hit on every send-time gate check and on every 15-second watchdog tick.
 *
 * **What `availMem` means in practice** (per AOSP source): the kernel's
 * `MemAvailable` from `/proc/meminfo`, which is `MemFree + reclaimable
 * caches`. It already accounts for what the OS expects to be able to
 * reclaim cheaply, so a value of e.g. 1 GiB really does mean "less than
 * 1 GiB is comfortably available before the OS has to start working hard."
 */
class AndroidMemoryHeadroomProvider(context: Context) : MemoryHeadroomProvider {

    private val activityManager: ActivityManager =
        context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    override fun availableBytes(): Long {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info.availMem
    }
}
