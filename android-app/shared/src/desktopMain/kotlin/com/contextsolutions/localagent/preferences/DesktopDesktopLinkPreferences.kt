package com.contextsolutions.localagent.preferences

import com.contextsolutions.localagent.platform.DesktopJsonStore
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Desktop [DesktopLinkPreferences] (PR #57), the counterpart of Android's
 * [SharedPreferencesDesktopLinkPreferences]. Backed by a [DesktopJsonStore] file
 * (one per repo, under the app-data dir). The whole [DesktopLinkConfig] is stored
 * as one JSON blob; a corrupt/missing value falls back to [DesktopLinkConfig.EMPTY].
 * A stable [DesktopLinkConfig.selfDeviceId] is minted on first read.
 *
 * On the desktop side the link is *hosted* (the relay QR is published here while
 * subscribed); the desktop never routes its own chat to itself, so
 * [DesktopLinkConfig.enabled] stays false. The desktop uses this store to hold its
 * own device id and to record which mobile has paired.
 */
@OptIn(ExperimentalUuidApi::class)
class DesktopDesktopLinkPreferences(private val store: DesktopJsonStore) : DesktopLinkPreferences {

    private val json = Json { ignoreUnknownKeys = true }

    private val state = MutableStateFlow(ensureDeviceId(load()))

    private fun load(): DesktopLinkConfig = store.getString(KEY_CONFIG)
        ?.let { runCatching { json.decodeFromString(DesktopLinkConfig.serializer(), it) }.getOrNull() }
        ?: DesktopLinkConfig.EMPTY

    private fun ensureDeviceId(config: DesktopLinkConfig): DesktopLinkConfig {
        if (config.selfDeviceId.isNotBlank()) return config
        val seeded = config.copy(selfDeviceId = "dev-${Uuid.random()}")
        store.putString(KEY_CONFIG, json.encodeToString(DesktopLinkConfig.serializer(), seeded))
        return seeded
    }

    override fun config(): DesktopLinkConfig = state.value

    override fun configFlow(): Flow<DesktopLinkConfig> = state.asStateFlow()

    override fun setConfig(config: DesktopLinkConfig) {
        val withId = if (config.selfDeviceId.isNotBlank()) config
        else config.copy(selfDeviceId = state.value.selfDeviceId)
        if (state.value == withId) return // idempotent
        state.value = withId
        store.putString(KEY_CONFIG, json.encodeToString(DesktopLinkConfig.serializer(), withId))
    }

    private companion object {
        const val KEY_CONFIG = "desktop_link_config"
    }
}
