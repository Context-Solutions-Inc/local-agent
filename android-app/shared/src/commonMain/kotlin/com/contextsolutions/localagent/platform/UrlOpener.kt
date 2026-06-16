package com.contextsolutions.localagent.platform

/**
 * Opens a web URL in the platform's default browser (docs/DESKTOP_PORT_PLAN.md
 * Phase 9). The onboarding key/token screens link out to the Brave / Hugging
 * Face dashboards; Android did this with an `ACTION_VIEW` `Intent`, which isn't
 * portable, so shared `:ui` screens go through this Koin-bound seam.
 *
 * Android binds an `Intent`-backed actual; desktop uses `java.awt.Desktop`.
 * Failures are swallowed (best-effort — a missing browser shouldn't crash).
 */
interface UrlOpener {
    fun openUrl(url: String)
}
