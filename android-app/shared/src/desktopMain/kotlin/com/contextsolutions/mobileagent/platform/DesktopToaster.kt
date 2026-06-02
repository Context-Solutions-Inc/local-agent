package com.contextsolutions.mobileagent.platform

/**
 * Desktop [Toaster] actual — logs to stderr. Memory backup feedback on desktop
 * is also conveyed by the screen's busy spinner + result dialogs; a richer
 * transient surface (tray notification / Compose snackbar) can replace this
 * behind the same interface later.
 */
class DesktopToaster : Toaster {
    override fun show(message: String) {
        System.err.println("[toast] $message")
    }
}
