package com.contextsolutions.localagent.sync

import com.contextsolutions.localagent.platform.DesktopJsonStore

/**
 * Desktop [LastSyncStore] (PR #70) — shares the `sync_state.json` file with
 * [DesktopSyncWatermarkStore]. `null` when absent (never synced).
 */
class DesktopLastSyncStore(private val store: DesktopJsonStore) : LastSyncStore {
    override fun get(): Long? = store.getString(KEY)?.toLongOrNull()
    override fun set(epochMs: Long) {
        store.putString(KEY, epochMs.toString())
    }

    private companion object {
        const val KEY = "last_sync_ms"
    }
}
