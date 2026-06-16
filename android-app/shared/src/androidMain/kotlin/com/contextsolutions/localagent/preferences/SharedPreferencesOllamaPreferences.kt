package com.contextsolutions.localagent.preferences

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Android [OllamaPreferences] backed by a non-encrypted SharedPreferences file.
 * Host/port/model are connection configuration, not credentials, so the
 * EncryptedSharedPreferences machinery used for the Brave key is unnecessary.
 *
 * The whole [OllamaConfig] is stored as one JSON blob; a corrupt/missing value
 * falls back to [OllamaConfig.EMPTY] so a schema change can't crash an install.
 */
class SharedPreferencesOllamaPreferences(context: Context) : OllamaPreferences {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val state = MutableStateFlow(load())

    private fun load(): OllamaConfig = prefs.getString(KEY_CONFIG, null)
        ?.let { runCatching { json.decodeFromString(OllamaConfig.serializer(), it) }.getOrNull() }
        ?: OllamaConfig.EMPTY

    override fun config(): OllamaConfig = state.value

    override fun configFlow(): Flow<OllamaConfig> = state.asStateFlow()

    override fun setConfig(config: OllamaConfig) {
        if (state.value == config) return // idempotent
        state.value = config
        prefs.edit()
            .putString(KEY_CONFIG, json.encodeToString(OllamaConfig.serializer(), config))
            .apply()
    }

    private companion object {
        private const val PREFS_NAME = "ollama_prefs"
        private const val KEY_CONFIG = "ollama_config"
    }
}
