package com.contextsolutions.localagent.ui.util

import androidx.compose.runtime.Composable

/**
 * One-shot screen-reader announcement (invariant #26 — a `liveRegion` on the
 * streaming bubble would re-read the whole growing string, so a completed
 * assistant turn is announced exactly once instead). Android routes to
 * `View.announceForAccessibility`; desktop is a no-op.
 */
fun interface AccessibilityAnnouncer {
    fun announce(text: String)
}

@Composable
expect fun rememberAccessibilityAnnouncer(): AccessibilityAnnouncer
