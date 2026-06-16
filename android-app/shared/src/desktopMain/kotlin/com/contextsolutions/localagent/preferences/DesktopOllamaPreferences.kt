package com.contextsolutions.localagent.preferences

import com.contextsolutions.localagent.platform.DesktopJsonStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Desktop [OllamaPreferences] (PR #56), the counterpart of Android's
 * [SharedPreferencesOllamaPreferences]. Backed by a [DesktopJsonStore] file
 * (one per repo, under the app-data dir). The whole [OllamaConfig] is stored as
 * one JSON blob; a corrupt/missing value falls back to [OllamaConfig.EMPTY].
 */
class DesktopOllamaPreferences(private val store: DesktopJsonStore) : OllamaPreferences {

    private val json = Json { ignoreUnknownKeys = true }

    private val state = MutableStateFlow(load())

    private fun load(): OllamaConfig = store.getString(KEY_CONFIG)
        ?.let { runCatching { json.decodeFromString(OllamaConfig.serializer(), it) }.getOrNull() }
        ?: OllamaConfig.EMPTY

    override fun config(): OllamaConfig = state.value

    override fun configFlow(): Flow<OllamaConfig> = state.asStateFlow()

    override fun setConfig(config: OllamaConfig) {
        if (state.value == config) return // idempotent
        state.value = config
        store.putString(KEY_CONFIG, json.encodeToString(OllamaConfig.serializer(), config))
    }

    private companion object {
        const val KEY_CONFIG = "ollama_config"
    }
}
