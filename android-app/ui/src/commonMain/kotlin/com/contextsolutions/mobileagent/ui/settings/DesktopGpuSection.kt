package com.contextsolutions.mobileagent.ui.settings

import androidx.compose.runtime.Composable

/**
 * Desktop-only GPU device pin (PR #78): pick which GPU `llama-server` offloads to so a
 * multi-GPU box ignores the slow integrated GPU and runs entirely on the discrete card.
 * "Detect devices" enumerates the host's GPUs (`llama-server --list-devices`). Renders nothing
 * on Android (LiteRT-LM, no subprocess) — so the common SettingsScreen can call it
 * unconditionally. Reads/writes the concrete `DesktopGpuPreferences` and enumerates via
 * `LlamaServerDevices` from Koin (desktop-only, off any shared interface, invariant #45).
 */
@Composable
expect fun DesktopGpuSection()
