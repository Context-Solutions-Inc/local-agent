package com.contextsolutions.localagent.ui.settings

import androidx.compose.runtime.Composable

/**
 * Desktop-only read-aloud voice picker (PR #66): engine (Linux output module),
 * voice, speech-rate slider, and a "Test voice" button. Renders nothing on
 * Android, where the OS owns voice selection — so the common SettingsScreen can
 * call it unconditionally. Reads/writes the concrete `DesktopTtsPreferences` and
 * enumerates options via `DesktopTtsVoices` from Koin (these are desktop-only and
 * deliberately off the shared `TtsPreferences` interface, invariant #45).
 */
@Composable
expect fun DesktopVoiceSection()
