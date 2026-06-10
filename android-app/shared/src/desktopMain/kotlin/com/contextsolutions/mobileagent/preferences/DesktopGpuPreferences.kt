package com.contextsolutions.mobileagent.preferences

import com.contextsolutions.mobileagent.platform.DesktopJsonStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop-only GPU device pin (PR #78) persisted in a [DesktopJsonStore] file.
 *
 * The Vulkan `llama-server` enumerates every Vulkan device and, by default, can offload to the
 * wrong one (a slow integrated GPU) on a multi-GPU box. Pinning it to a single device id
 * (e.g. `Vulkan1` for a discrete NVIDIA card) makes it ignore the iGPU and run entirely on the
 * fast card. The pin rides into the launch args as `--device <id>` only on the GPU variant; a
 * blank pin (the default) means "Auto — all GPUs", llama-server's normal behaviour.
 *
 * Like the desktop-only UI-zoom on `DesktopThemePreferences` and the voice settings on
 * `DesktopTtsPreferences` (invariant #45), this stays on the concrete class — mobile has no
 * equivalent (Android keeps LiteRT-LM). The Settings UI observes [devicePinFlow] and writes via
 * [setDevicePin]; the engine reads [devicePin] at server start; `Main.kt` drops the warm model
 * when it changes so the next turn re-launches the server pinned.
 */
class DesktopGpuPreferences(private val store: DesktopJsonStore) {

    private val state = MutableStateFlow(store.getString(KEY_DEVICE_PIN)?.takeIf { it.isNotBlank() })

    /** The pinned device id, or null for "Auto — all GPUs". */
    fun devicePin(): String? = state.value
    fun devicePinFlow(): Flow<String?> = state.asStateFlow()
    fun setDevicePin(pin: String?) {
        val normalized = pin?.trim()?.takeIf { it.isNotEmpty() }
        if (state.value == normalized) return
        state.value = normalized
        store.putString(KEY_DEVICE_PIN, normalized.orEmpty())
    }

    private companion object {
        const val KEY_DEVICE_PIN = "device_pin"
    }
}
