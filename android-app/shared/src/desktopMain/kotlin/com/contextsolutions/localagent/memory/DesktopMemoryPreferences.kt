package com.contextsolutions.localagent.memory

import com.contextsolutions.localagent.platform.DesktopJsonStore

/**
 * Desktop [MemoryPreferences] — the memory-creation toggle persisted in a small
 * JSON file (Phase 6), the counterpart of Android's `SharedPreferencesMemoryPreferences`.
 * Non-secret, so a plain file is fine (the DB itself carries the user's memories).
 */
class DesktopMemoryPreferences(private val store: DesktopJsonStore) : MemoryPreferences {

    override fun creationEnabled(): Boolean =
        store.getString(KEY_CREATION_ENABLED)?.toBooleanStrictOrNull()
            ?: MemoryPreferences.DEFAULT_CREATION_ENABLED

    override fun setCreationEnabled(enabled: Boolean) {
        store.putString(KEY_CREATION_ENABLED, enabled.toString())
    }

    private companion object {
        const val KEY_CREATION_ENABLED = "creation_enabled"
    }
}
