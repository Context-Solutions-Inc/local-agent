package com.contextsolutions.localagent.sync

import com.contextsolutions.localagent.platform.IosJsonStore

/**
 * iOS [SyncWatermarkStore] backed by a JSON prefs file (mirrors Android's SharedPreferences store).
 * The watermark is the last-seen desktop change cursor; reset to 0 forces a full re-pull.
 */
class IosSyncWatermarkStore(
    private val store: IosJsonStore,
    private val key: String = "watermark",
) : SyncWatermarkStore {
    override fun get(): Long = store.getString(key)?.toLongOrNull() ?: 0L
    override fun set(value: Long) = store.putString(key, value.toString())
}

/** iOS [LastSyncStore] backed by a JSON prefs file — the last-successful-sync wall-clock (Jobs UI). */
class IosLastSyncStore(
    private val store: IosJsonStore,
    private val key: String = "last_sync",
) : LastSyncStore {
    override fun get(): Long? = store.getString(key)?.toLongOrNull()
    override fun set(epochMs: Long) = store.putString(key, epochMs.toString())
}
