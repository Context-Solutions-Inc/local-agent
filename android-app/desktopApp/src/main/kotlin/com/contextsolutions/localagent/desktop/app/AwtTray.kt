package com.contextsolutions.localagent.desktop.app

import com.contextsolutions.localagent.notification.AppNotification
import com.contextsolutions.localagent.notification.NotificationKind
import com.contextsolutions.localagent.notification.NotificationPresenter
import java.awt.Color
import java.awt.Font
import java.awt.Image
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * Colours + font size for the themed tray menu, supplied per-show by the caller so the
 * menu tracks the app's light/dark theme and UI-zoom (PR #71). Plain AWT [Color]s — the
 * menu is Swing, deliberately not Compose (see [AwtTray]).
 */
data class TrayMenuStyle(
    val background: Color,
    val foreground: Color,
    val border: Color,
    val fontSize: Int,
)

/**
 * Raw AWT system-tray integration, replacing Compose Desktop's `Tray()` composable
 * (issue #68: the tray icon + menu text were too small). Compose's `Tray`/`Item`
 * expose no font hook and always render the icon at the tray's reported preferred
 * size, so neither the icon nor the menu text could be enlarged through them — only
 * the AWT primitives ([TrayIcon] + [PopupMenu]) let us scale both.
 *
 * Everything here is best-effort and guarded: the caller has already probed
 * `SystemTray.isSupported()` (which lies on GNOME/Wayland — see Main.kt), and
 * [install] swallows any peer-creation failure by returning null so the caller
 * falls back to the logging presenter + close-to-quit, exactly as when the tray is
 * unsupported.
 *
 * - **Icon:** a tray-only full-bleed navy tile (`tray.png`) rendered at the reported
 *   `trayIconSize` with AWT auto-resize ON, so the icon tracks whatever slot the DE
 *   reports across resolution / DPI changes.
 * - **Menu:** a STYLED Swing [JPopupMenu], not the native AWT [java.awt.PopupMenu]
 *   (unstyleable Motif/X11 look on Linux) and not a Compose `Window` (PR #71 proved a
 *   custom top-level window can't get the click: AWT's `TrayIcon` grabs the pointer on
 *   the popup trigger to show its own popup, so the next click is swallowed by the tray
 *   icon — it fell through to `onShow`). `JPopupMenu` cooperates with that grab and, via
 *   the documented invisible-owner-window trick, receives input reliably on Linux. We
 *   colour it from a per-show [TrayMenuStyle] so it tracks the app's light/dark theme.
 *   Left-click / activate routes to `onShow`; the menu offers Show + Shut down.
 */
class AwtTray private constructor(
    private val tray: SystemTray,
    private val icon: TrayIcon,
) {
    /** A [NotificationPresenter] that surfaces toasts via this tray icon. */
    val notificationPresenter: NotificationPresenter = object : NotificationPresenter {
        override fun present(notification: AppNotification) {
            icon.displayMessage(
                notification.title,
                notification.body,
                when (notification.kind) {
                    NotificationKind.ALARM, NotificationKind.TIMER -> TrayIcon.MessageType.WARNING
                    NotificationKind.TASK, NotificationKind.JOB, NotificationKind.INFO -> TrayIcon.MessageType.INFO
                },
            )
        }

        override fun dismiss(id: String) {
            // Tray toasts are transient; nothing to actively clear.
        }
    }

    /** Detach the icon from the system tray (idempotent, never throws). */
    fun remove() {
        runCatching { tray.remove(icon) }
    }

    companion object {
        /**
         * Build + register the tray icon. Returns null if anything throws — the caller
         * then keeps the logging presenter and close-to-quit behaviour.
         *
         * @param onShow fired on left-click / activate (double-click on most DEs) and by
         *   the menu's "Show" item.
         * @param onQuit fired by the menu's "Shut down" item.
         * @param menuStyle supplies the menu colours/font at show time so it tracks the
         *   current theme + UI-zoom (PR #71).
         */
        fun install(
            tooltip: String,
            onShow: () -> Unit,
            onQuit: () -> Unit,
            menuStyle: () -> TrayMenuStyle,
        ): AwtTray? = runCatching {
            val tray = SystemTray.getSystemTray()
            // Render at the tray's reported slot size and let AWT auto-fit the icon. The
            // earlier overfill hack (render TRAY_SLOT_FACTOR× larger × the HiDPI scale)
            // was tuned to one display and over-sized the icon after a resolution change,
            // cropping it to a corner — simple sizing tracks whatever slot the DE reports.
            val slot = tray.trayIconSize
            val image = loadIcon(slot.width, slot.height)

            val icon = TrayIcon(image, tooltip).apply {
                // Let AWT scale the image to the actual slot, robust across resolution /
                // DPI changes.
                isImageAutoSize = true
                // Activating the icon (single/double-click depending on DE) shows the window.
                addActionListener { onShow() }
                // Right-click → styled Swing JPopupMenu. `isPopupTrigger` fires on press on
                // some platforms and release on others, so check both; `shown` de-dupes.
                addMouseListener(object : MouseAdapter() {
                    private var shownForGesture = false
                    // Reset per gesture on press; the trigger may be press (Linux/X11) or
                    // release (Windows), and some platforms flag both — guard so we show once.
                    override fun mousePressed(e: MouseEvent) { shownForGesture = false; maybePopup(e) }
                    override fun mouseReleased(e: MouseEvent) { maybePopup(e) }
                    private fun maybePopup(e: MouseEvent) {
                        if (e.isPopupTrigger && !shownForGesture) {
                            shownForGesture = true
                            showTrayMenu(e.xOnScreen, e.yOnScreen, menuStyle(), onShow, onQuit)
                        }
                    }
                })
            }
            tray.add(icon)
            AwtTray(tray, icon)
        }.onFailure {
            System.err.println("[desktopApp] AWT tray install failed: ${it.message}")
        }.getOrNull()

        /**
         * Show the themed Swing context menu at the cursor. On Linux a [JPopupMenu] shown
         * from a tray icon only receives input when anchored to a real, visible owner
         * window (the documented workaround — without it the menu shows but its clicks are
         * eaten by AWT's tray pointer grab, PR #71). So we map a 1×1 invisible [JWindow] at
         * the cursor, show the popup on it, and dispose the owner when the menu closes.
         */
        private fun showTrayMenu(
            x: Int,
            y: Int,
            style: TrayMenuStyle,
            onShow: () -> Unit,
            onQuit: () -> Unit,
        ) = SwingUtilities.invokeLater {
            val font = Font(Font.SANS_SERIF, Font.PLAIN, style.fontSize)
            fun item(label: String, action: () -> Unit) = JMenuItem(label).apply {
                isOpaque = true
                background = style.background
                foreground = style.foreground
                this.font = font
                border = EmptyBorder(6, 16, 6, 16)
                addActionListener { action() }
            }
            val popup = JPopupMenu().apply {
                // Heavyweight: the owner is 1×1, so the menu must be its own native window.
                isLightWeightPopupEnabled = false
                background = style.background
                border = BorderFactory.createLineBorder(style.border, 1)
                add(item("Show", onShow))
                add(item("Shut down", onQuit))
            }
            val owner = JWindow().apply {
                isAlwaysOnTop = true
                setSize(1, 1)
                setLocation(x, y)
                isVisible = true
                toFront()
            }
            popup.addPopupMenuListener(object : PopupMenuListener {
                override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {}
                override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {
                    SwingUtilities.invokeLater { owner.dispose() }
                }
                override fun popupMenuCanceled(e: PopupMenuEvent) {
                    SwingUtilities.invokeLater { owner.dispose() }
                }
            })
            popup.show(owner, 0, 0)
        }

        private fun loadIcon(width: Int, height: Int): Image {
            // tray.png is a full-bleed navy square with an enlarged bubble (generate_icons.py),
            // distinct from the rounded window/app icon.png — the tray slot is tiny, so it wants
            // the navy edge-to-edge and the glyph filling the tile, not a small rounded plate.
            val src = AwtTray::class.java.getResourceAsStream("/tray.png")!!
                .use { ImageIO.read(it) }
            // Pre-scale to the reported slot size with high-quality bilinear interpolation;
            // AWT auto-resize then fits it to the real slot. Filling the whole canvas — the
            // source is already a centred full-bleed square — keeps navy to every edge.
            val canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val g = canvas.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.drawImage(src, 0, 0, width, height, null)
            g.dispose()
            return canvas
        }
    }
}
