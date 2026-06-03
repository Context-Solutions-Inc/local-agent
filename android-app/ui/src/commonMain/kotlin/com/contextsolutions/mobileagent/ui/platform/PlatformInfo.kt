package com.contextsolutions.mobileagent.ui.platform

/**
 * True on the desktop (Compose Desktop) app, false on Android (PR #57). Used by
 * shared `:ui` screens to vary platform-specific UI without a separate screen —
 * e.g. the desktop hosts the link (shows a QR + connected phone instead of the
 * mobile pairing controls) and hides the mobile-only todo/timer/alarm entries.
 */
expect val isDesktopPlatform: Boolean
