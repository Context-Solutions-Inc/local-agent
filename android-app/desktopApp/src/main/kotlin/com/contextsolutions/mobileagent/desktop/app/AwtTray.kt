package com.contextsolutions.mobileagent.desktop.app

import com.contextsolutions.mobileagent.notification.AppNotification
import com.contextsolutions.mobileagent.notification.NotificationKind
import com.contextsolutions.mobileagent.notification.NotificationPresenter
import java.awt.CheckboxMenuItem
import java.awt.Font
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.roundToInt

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
 * - **Icon:** a tray-only full-bleed navy tile (`tray.png`) rendered [TRAY_SLOT_FACTOR]×
 *   larger than the reported `trayIconSize` (which GNOME/Pop under-reports), with
 *   auto-resize OFF, so the navy overfills the real slot instead of leaving light edges.
 * - **Menu:** every item uses a [SCALE]× font.
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
                    NotificationKind.TASK, NotificationKind.INFO -> TrayIcon.MessageType.INFO
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
        /** 2× menu-item font (issue #68 — the tray menu text was too small). */
        private const val SCALE = 2

        /**
         * How much larger than the reported `trayIconSize` to render the icon, to cover
         * the real (under-reported) slot on GNOME/Pop. Raise if light edges remain on the
         * right/bottom; lower if the centred bubble looks pushed toward a corner.
         */
        private const val TRAY_SLOT_FACTOR = 1.35

        /**
         * AWT's heavyweight [PopupMenu] has no reliable default-font getter (the font
         * is null until the native peer is created), so we derive the menu font from a
         * fixed base point size rather than the look-and-feel.
         */
        private const val MENU_BASE_PT = 12

        /**
         * Build + register the tray icon. Returns null if anything throws — the caller
         * then keeps the logging presenter and close-to-quit behaviour.
         */
        fun install(
            tooltip: String,
            pausedInitially: Boolean,
            onShow: () -> Unit,
            onTogglePause: (Boolean) -> Unit,
            onQuit: () -> Unit,
        ): AwtTray? = runCatching {
            val tray = SystemTray.getSystemTray()
            val preferred = tray.trayIconSize // the tray's slot size, in LOGICAL px
            // HiDPI / fractional scaling: the embedded tray-icon window is sized in
            // physical pixels, but trayIconSize is logical. A logical-sized image is
            // then anchored top-left and the bare right/bottom of the slot shows through
            // as light "lines". Render at the physical size so the navy fills the slot.
            // The DE sizes the real tray slot, and on GNOME/Pop trayIconSize UNDER-reports
            // it (reports 24 while the slot is larger), so a slot-sized tile is pinned
            // top-left and the bare right/bottom shows as light "lines". Render a square
            // TRAY_SLOT_FACTOR× larger than the reported size (× the HiDPI displayScale) so
            // the navy overfills the slot. Too small → white edges; too large → the centred
            // bubble is pushed toward a corner. One knob, tune from the logged size below.
            val scale = displayScale()
            val side = (maxOf(preferred.width, preferred.height) * scale * TRAY_SLOT_FACTOR)
                .roundToInt()
                .coerceAtLeast(maxOf(preferred.width, preferred.height))
            val image = loadIcon(side, side)

            val menuFont = Font(Font.SANS_SERIF, Font.PLAIN, MENU_BASE_PT * SCALE)
            val menu = PopupMenu().apply {
                font = menuFont
                add(
                    MenuItem("Show").apply {
                        font = menuFont
                        addActionListener { onShow() }
                    },
                )
                add(
                    CheckboxMenuItem("Pause queue", pausedInitially).apply {
                        font = menuFont
                        addItemListener { onTogglePause(state) }
                    },
                )
                addSeparator()
                add(
                    MenuItem("Quit").apply {
                        font = menuFont
                        addActionListener { onQuit() }
                    },
                )
            }

            val icon = TrayIcon(image, tooltip, menu).apply {
                // Image is already the exact slot size, so auto-resize must stay OFF: its
                // rescale filter bleeds a light 1px edge on the right/bottom (the "white
                // lines"). 1:1 blit = navy edge-to-edge, no artifact.
                isImageAutoSize = false
                // Activating the icon (double-click on most DEs) shows the window.
                addActionListener { onShow() }
            }
            tray.add(icon)
            AwtTray(tray, icon)
        }.onFailure {
            System.err.println("[desktopApp] AWT tray install failed: ${it.message}")
        }.getOrNull()

        /** Display scale factor (2.0 on HiDPI, 1.25/1.5 on fractional). 1.0 fallback. */
        private fun displayScale(): Double = runCatching {
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX
        }.getOrDefault(1.0).coerceAtLeast(1.0)

        private fun loadIcon(width: Int, height: Int): Image {
            // tray.png is a full-bleed navy square with an enlarged bubble (generate_icons.py),
            // distinct from the rounded window/app icon.png — the tray slot is tiny, so it wants
            // the navy edge-to-edge and the glyph filling the tile, not a small rounded plate.
            val src = AwtTray::class.java.getResourceAsStream("/tray.png")!!
                .use { ImageIO.read(it) }
            // Pre-scale ONCE to the exact slot size (high-quality bilinear) so the TrayIcon
            // never rescales it (auto-resize off). Filling the whole canvas — the source is
            // already a centred full-bleed square — keeps navy to every edge, no border gap.
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
