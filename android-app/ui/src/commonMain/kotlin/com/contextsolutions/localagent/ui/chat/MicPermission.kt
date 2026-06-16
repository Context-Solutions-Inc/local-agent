package com.contextsolutions.localagent.ui.chat

import androidx.compose.runtime.Composable

/**
 * Microphone-dictation availability + permission for the chat mic toggle
 * (docs/DESKTOP_PORT_PLAN.md Phase 9, invariant #42). Android needs the
 * `RECORD_AUDIO` runtime permission and a recognition service; desktop's Vosk
 * needs neither (it captures the default line directly).
 *
 * [available] gates the mic button's enabled state (device can dictate at all);
 * [granted] reports whether the runtime permission is held; [request] prompts for
 * it and reports the result. The actual is a `@Composable` because Android
 * registers a permission launcher in composition.
 */
interface MicPermission {
    /** Device supports dictation (Android: a recognition service; desktop: always true). */
    val available: Boolean

    /** Runtime permission held (Android: RECORD_AUDIO; desktop: always true). */
    val granted: Boolean

    /** Request the permission; [onResult] receives whether it was granted. */
    fun request(onResult: (Boolean) -> Unit)
}

@Composable
expect fun rememberMicPermission(): MicPermission
