package com.contextsolutions.localagent.memory

import android.content.Context

/**
 * Android-backed [MemoryPreferences] using a plain `SharedPreferences`
 * file. The toggle is non-secret (it's just whether to extract memories
 * locally) so we don't need `EncryptedSharedPreferences` here — the DB
 * itself is on the platform's standard data-at-rest encryption (PRD §4.4).
 */
class SharedPreferencesMemoryPreferences(context: Context) : MemoryPreferences {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun creationEnabled(): Boolean =
        prefs.getBoolean(KEY_CREATION_ENABLED, MemoryPreferences.DEFAULT_CREATION_ENABLED)

    override fun setCreationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CREATION_ENABLED, enabled).apply()
    }

    private companion object {
        private const val PREFS_NAME = "memory_preferences"
        private const val KEY_CREATION_ENABLED = "creation_enabled"
    }
}
