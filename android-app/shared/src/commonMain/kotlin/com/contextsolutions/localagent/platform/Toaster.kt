package com.contextsolutions.localagent.platform

/**
 * Shows a short transient message (docs/DESKTOP_PORT_PLAN.md Phase 9). The
 * Memory screen surfaces export/import feedback this way. Android used
 * `Toast`, which isn't portable, so shared `:ui` screens go through this
 * Koin-bound seam.
 *
 * Android binds a `Toast`-backed actual; desktop logs (a richer desktop
 * surface — tray notification / snackbar — can swap in behind this later).
 */
interface Toaster {
    fun show(message: String)
}
