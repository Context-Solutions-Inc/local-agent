package com.contextsolutions.localagent.platform

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * iOS [UrlOpener] (PR #41) — opens a URL in Safari. Best-effort: a malformed URL is
 * ignored.
 *
 * Uses the modern `openURL:options:completionHandler:`. The legacy single-arg
 * `openURL(_:)` is deprecated and current iOS **force-returns false** ("BUG IN CLIENT
 * OF UIKIT: … needs to migrate to the non-deprecated UIApplication.open(...)"), so
 * citation links never opened. Called from a Compose click handler (main thread), as
 * this API requires.
 */
class IosUrlOpener : UrlOpener {
    override fun openUrl(url: String) {
        val nsUrl = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(
            nsUrl,
            options = emptyMap<Any?, Any?>(),
            completionHandler = null,
        )
    }
}
