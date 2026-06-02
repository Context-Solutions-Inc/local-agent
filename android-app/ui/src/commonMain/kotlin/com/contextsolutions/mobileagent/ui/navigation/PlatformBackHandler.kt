package com.contextsolutions.mobileagent.ui.navigation

import androidx.compose.runtime.Composable

/**
 * Platform back-navigation seam (docs/DESKTOP_PORT_PLAN.md Phase 3/9).
 *
 * The shared navigation host has explicit on-screen back affordances, but
 * Android also has a system back gesture/button that must map to the same
 * route change. Compose's `androidx.activity.compose.BackHandler` is
 * Android-only, so this `expect` lets shared screens register a back action
 * that the system honours on Android and is a no-op on desktop (where the
 * window's close button is the OS-level back, and routes are driven by the
 * in-app buttons).
 *
 * @param enabled when false the handler is inert (the event falls through).
 * @param onBack invoked when the platform back action fires.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
