package com.contextsolutions.localagent.sync

import com.contextsolutions.localagent.platform.DesktopJsonStore

/** Desktop [SyncWatermarkStore] backed by a [DesktopJsonStore] file (PR #57). */
class DesktopSyncWatermarkStore(private val store: DesktopJsonStore) : SyncWatermarkStore {
    override fun get(): Long = store.getString(KEY)?.toLongOrNull() ?: 0L
    override fun set(value: Long) {
        store.putString(KEY, value.toString())
    }

    private companion object {
        const val KEY = "watermark_ms"
    }
}
