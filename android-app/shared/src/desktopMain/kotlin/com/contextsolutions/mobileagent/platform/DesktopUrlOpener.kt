package com.contextsolutions.mobileagent.platform

import java.awt.Desktop
import java.net.URI

/**
 * Desktop [UrlOpener] actual — `java.awt.Desktop.browse`. Best-effort: guarded
 * for headless environments / unsupported platforms where Desktop or the
 * BROWSE action is unavailable.
 */
class DesktopUrlOpener : UrlOpener {
    override fun openUrl(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported()) {
                val desktop = Desktop.getDesktop()
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(URI(url))
                }
            }
        }
    }
}
