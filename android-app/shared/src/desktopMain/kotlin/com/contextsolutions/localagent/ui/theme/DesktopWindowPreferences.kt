package com.contextsolutions.localagent.ui.theme

import com.contextsolutions.localagent.platform.DesktopJsonStore

/**
 * Desktop-only persistence for the main window's geometry — size, position, and
 * whether it was maximized — so the app reopens the way the user left it. The
 * counterpart of the theme/zoom prefs ([DesktopThemePreferences]); a separate
 * JSON file (`window_prefs.json`) since geometry is a window concern, not a theme.
 *
 * Stores raw dp floats (kept Compose-free here; `Main.kt` maps them to
 * `DpSize`/`WindowPosition`/`WindowPlacement`). Width/height are written only
 * while the window is *floating* — saving the maximized footprint would clobber
 * the restore size, so a reopened-maximized window un-maximizes to its real size.
 */
class DesktopWindowPreferences(private val store: DesktopJsonStore) {

    /** Persisted geometry; null fields fall back to the platform default. */
    data class Geometry(
        val widthDp: Float,
        val heightDp: Float,
        val xDp: Float?,
        val yDp: Float?,
        val maximized: Boolean,
    )

    /** Last saved geometry, or null on first run / unreadable store. */
    fun load(): Geometry? {
        val w = store.getString(KEY_W)?.toFloatOrNull() ?: return null
        val h = store.getString(KEY_H)?.toFloatOrNull() ?: return null
        return Geometry(
            widthDp = w,
            heightDp = h,
            xDp = store.getString(KEY_X)?.toFloatOrNull(),
            yDp = store.getString(KEY_Y)?.toFloatOrNull(),
            maximized = store.getString(KEY_MAX)?.toBooleanStrictOrNull() ?: false,
        )
    }

    /**
     * Persist the current geometry. Pass `widthDp`/`heightDp` null to keep the
     * previously stored size (used when maximized so the restore size survives).
     */
    fun save(widthDp: Float?, heightDp: Float?, xDp: Float?, yDp: Float?, maximized: Boolean) {
        if (widthDp != null) store.putString(KEY_W, widthDp.toString())
        if (heightDp != null) store.putString(KEY_H, heightDp.toString())
        if (xDp != null) store.putString(KEY_X, xDp.toString())
        if (yDp != null) store.putString(KEY_Y, yDp.toString())
        store.putString(KEY_MAX, maximized.toString())
    }

    private companion object {
        const val KEY_W = "width_dp"
        const val KEY_H = "height_dp"
        const val KEY_X = "x_dp"
        const val KEY_Y = "y_dp"
        const val KEY_MAX = "maximized"
    }
}
