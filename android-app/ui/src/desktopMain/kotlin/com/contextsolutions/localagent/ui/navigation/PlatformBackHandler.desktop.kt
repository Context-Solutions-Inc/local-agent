package com.contextsolutions.localagent.ui.navigation

import androidx.compose.runtime.Composable

/**
 * Desktop actual: no-op. The desktop window's title-bar/close button is the
 * OS-level back affordance, and in-app navigation is driven entirely by the
 * explicit on-screen back buttons, so there is no ambient back event to
 * intercept. (An Escape-key binding can be layered in at the window level
 * later if desired without changing this contract.)
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Intentionally empty — no ambient platform back event on desktop.
}
