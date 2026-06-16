package com.contextsolutions.localagent.ui.settings

import androidx.compose.runtime.Composable

/**
 * Android no-op (PR #66): the read-aloud voice is the device's TTS engine setting,
 * so there's no in-app voice picker on mobile.
 */
@Composable
actual fun DesktopVoiceSection() = Unit
