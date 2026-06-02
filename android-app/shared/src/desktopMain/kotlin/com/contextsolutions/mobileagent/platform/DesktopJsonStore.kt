package com.contextsolutions.mobileagent.platform

import java.io.File
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Tiny synchronized string→string store persisted as a single JSON object file
 * (Phase 6, docs/DESKTOP_PORT_PLAN.md). The desktop counterpart of Android's
 * `SharedPreferences` / DataStore-Preferences — the file-based JSON pattern the
 * plan's seam table prescribes for desktop preferences. Desktop preference
 * repos compose one of these (one file per repo under the app-data dir).
 *
 * Robust to a missing/corrupt file (starts empty). Thread-safe via coarse
 * synchronization; preference writes are infrequent (settings saves).
 */
class DesktopJsonStore(private val file: File) {
    private val json = Json { isLenient = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val map: MutableMap<String, String> = load()

    private fun load(): MutableMap<String, String> = try {
        if (file.isFile) json.decodeFromString(serializer, file.readText()).toMutableMap() else mutableMapOf()
    } catch (_: Throwable) {
        mutableMapOf()
    }

    @Synchronized
    fun getString(key: String): String? = map[key]

    @Synchronized
    fun putString(key: String, value: String) {
        map[key] = value
        persist()
    }

    @Synchronized
    fun remove(key: String) {
        if (map.remove(key) != null) persist()
    }

    private fun persist() {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(serializer, map))
    }
}
