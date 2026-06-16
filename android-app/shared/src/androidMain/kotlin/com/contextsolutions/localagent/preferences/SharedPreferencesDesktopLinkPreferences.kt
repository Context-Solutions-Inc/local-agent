package com.contextsolutions.localagent.preferences

import android.content.Context
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Android [DesktopLinkPreferences] (PR #57; relay-only since PR #80), the
 * counterpart of the desktop [DesktopDesktopLinkPreferences]. Backed by a
 * non-encrypted SharedPreferences file — the scanned relay QR JSON is pairing
 * configuration (the high-value account secret lives in SecureStorage, not here).
 * The whole [DesktopLinkConfig] is one JSON blob decoded with `ignoreUnknownKeys`
 * (so blobs written before the LAN fields were dropped still load); a corrupt/missing
 * value falls back to [DesktopLinkConfig.EMPTY] so a schema change can't crash an
 * install. A stable [DesktopLinkConfig.selfDeviceId] is minted on first read.
 */
@OptIn(ExperimentalUuidApi::class)
class SharedPreferencesDesktopLinkPreferences(context: Context) : DesktopLinkPreferences {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val state = MutableStateFlow(ensureDeviceId(load()))

    private fun load(): DesktopLinkConfig = prefs.getString(KEY_CONFIG, null)
        ?.let { runCatching { json.decodeFromString(DesktopLinkConfig.serializer(), it) }.getOrNull() }
        ?: DesktopLinkConfig.EMPTY

    private fun ensureDeviceId(config: DesktopLinkConfig): DesktopLinkConfig {
        if (config.selfDeviceId.isNotBlank()) return config
        val seeded = config.copy(selfDeviceId = "dev-${Uuid.random()}")
        persist(seeded)
        return seeded
    }

    private fun persist(config: DesktopLinkConfig) {
        prefs.edit()
            .putString(KEY_CONFIG, json.encodeToString(DesktopLinkConfig.serializer(), config))
            .apply()
    }

    override fun config(): DesktopLinkConfig = state.value

    override fun configFlow(): Flow<DesktopLinkConfig> = state.asStateFlow()

    override fun setConfig(config: DesktopLinkConfig) {
        // Preserve the minted device id even if a caller passes a blank one.
        val withId = if (config.selfDeviceId.isNotBlank()) config
        else config.copy(selfDeviceId = state.value.selfDeviceId)
        if (state.value == withId) return // idempotent
        state.value = withId
        persist(withId)
    }

    private companion object {
        const val PREFS_NAME = "desktop_link_prefs"
        const val KEY_CONFIG = "desktop_link_config"
    }
}
