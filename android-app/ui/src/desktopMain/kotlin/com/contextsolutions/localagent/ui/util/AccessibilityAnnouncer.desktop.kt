package com.contextsolutions.localagent.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Desktop actual: no-op. Screen-reader integration on desktop is out of scope for
 * v1.0 (Compose Desktop has no portable announcement API equivalent to Android's).
 */
@Composable
actual fun rememberAccessibilityAnnouncer(): AccessibilityAnnouncer =
    remember { AccessibilityAnnouncer { } }
