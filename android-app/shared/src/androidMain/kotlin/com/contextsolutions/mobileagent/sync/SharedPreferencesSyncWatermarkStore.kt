package com.contextsolutions.mobileagent.sync

import android.content.Context

/** Android [SyncWatermarkStore] backed by a tiny SharedPreferences file (PR #57). */
class SharedPreferencesSyncWatermarkStore(context: Context) : SyncWatermarkStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    override fun get(): Long = prefs.getLong(KEY, 0L)
    override fun set(value: Long) {
        prefs.edit().putLong(KEY, value).apply()
    }

    private companion object {
        const val PREFS_NAME = "sync_state_prefs"
        const val KEY = "watermark_ms"
    }
}
