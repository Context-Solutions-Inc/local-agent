package com.contextsolutions.mobileagent.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Android actual: `View.announceForAccessibility` (deprecated in API 36 but still
 * the canonical one-shot TalkBack primitive, invariant #26). The suppression
 * stays narrowly scoped here.
 */
@Composable
actual fun rememberAccessibilityAnnouncer(): AccessibilityAnnouncer {
    val view = LocalView.current
    return remember(view) {
        AccessibilityAnnouncer { text ->
            @Suppress("DEPRECATION")
            view.announceForAccessibility(text)
        }
    }
}
