package com.contextsolutions.localagent.sync

/**
 * Persists the **wall-clock time of the last successful reconcile** (PR #70) —
 * set whenever [SyncController] reaches the desktop peer, even if zero records
 * moved. Distinct from [SyncWatermarkStore] (a monotonic content high-water-mark):
 * this is a UI-facing "Synced 4m ago" timestamp, surfaced on the Jobs screen so
 * the user knows how fresh the offline list is.
 *
 * `null` = never synced.
 */
interface LastSyncStore {
    fun get(): Long?
    fun set(epochMs: Long)
}
