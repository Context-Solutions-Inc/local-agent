package com.contextsolutions.localagent.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/**
 * Android actual: delegate to the activity-compose [BackHandler] so the
 * system back gesture/button drives the same route change as the on-screen
 * back arrow.
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}
