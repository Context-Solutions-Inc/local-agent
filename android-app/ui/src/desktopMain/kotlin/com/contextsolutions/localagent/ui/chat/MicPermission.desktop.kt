package com.contextsolutions.localagent.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Desktop actual: Vosk captures the default audio line with no OS permission
 * prompt, so dictation is always available and "granted". If no acoustic model
 * is present `VoskDictation.start()` simply no-ops (Phase 7 inc 8).
 */
@Composable
actual fun rememberMicPermission(): MicPermission = remember {
    object : MicPermission {
        override val available: Boolean = true
        override val granted: Boolean = true
        override fun request(onResult: (Boolean) -> Unit) = onResult(true)
    }
}
