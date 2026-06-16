package com.contextsolutions.localagent.notification

/**
 * Tiny OS-detection helper for desktop notification routing (PR #93), using the
 * same `os.name` substring idiom as [com.contextsolutions.localagent.voice.DesktopTtsSpeaker]
 * and `JobSettings.currentOsKey()`. Linux delivers via `notify-send`; macOS/Windows
 * keep the AWT tray toasts.
 */
object DesktopOs {
    private val osName: String = System.getProperty("os.name").orEmpty().lowercase()

    val isMac: Boolean get() = osName.contains("mac") || osName.contains("darwin")
    val isWindows: Boolean get() = osName.contains("win")
    val isLinux: Boolean get() = !isMac && !isWindows
}
