package com.contextsolutions.localagent.sync

import android.content.Context

/**
 * Android [LastSyncStore] (PR #70) — shares the `sync_state_prefs` file with
 * [SharedPreferencesSyncWatermarkStore]. Returns `null` when the key is absent
 * (never synced) rather than a 0L sentinel.
 */
class SharedPreferencesLastSyncStore(context: Context) : LastSyncStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    override fun get(): Long? = if (prefs.contains(KEY)) prefs.getLong(KEY, 0L) else null
    override fun set(epochMs: Long) {
        prefs.edit().putLong(KEY, epochMs).apply()
    }

    private companion object {
        const val PREFS_NAME = "sync_state_prefs"
        const val KEY = "last_sync_ms"
    }
}
