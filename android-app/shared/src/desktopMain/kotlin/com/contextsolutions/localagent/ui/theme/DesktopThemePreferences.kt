package com.contextsolutions.localagent.ui.theme

import com.contextsolutions.localagent.platform.DesktopJsonStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop [ThemePreferences] — the theme-mode preference persisted in a small
 * JSON file (theme_prefs.json), the counterpart of Android's
 * `SharedPreferencesThemePreferences`. A [MutableStateFlow] seeded from disk
 * mirrors the Android `SharedPreferences`-backed flow.
 */
class DesktopThemePreferences(private val store: DesktopJsonStore) : ThemePreferences {

    private val state = MutableStateFlow(
        runCatching { ThemeMode.valueOf(store.getString(KEY_MODE) ?: ThemeMode.Dark.name) }
            .getOrDefault(ThemeMode.Dark),
    )

    private val scaleState = MutableStateFlow(
        (store.getString(KEY_FONT_SCALE)?.toFloatOrNull() ?: FontScale.DEFAULT)
            .coerceIn(FontScale.MIN, FontScale.MAX),
    )

    private val familyState = MutableStateFlow(
        runCatching { AppFontFamily.valueOf(store.getString(KEY_FONT_FAMILY) ?: AppFontFamily.System.name) }
            .getOrDefault(AppFontFamily.System),
    )

    // Desktop-only whole-UI zoom (Ctrl/Cmd +/-). Not on the ThemePreferences
    // interface — mobile has no equivalent. Scales LocalDensity.density app-wide.
    private val zoomState = MutableStateFlow(
        (store.getString(KEY_UI_ZOOM)?.toFloatOrNull() ?: UiZoom.DEFAULT)
            .coerceIn(UiZoom.MIN, UiZoom.MAX),
    )

    override fun themeMode(): ThemeMode = state.value
    override fun themeModeFlow(): Flow<ThemeMode> = state.asStateFlow()
    override fun setThemeMode(mode: ThemeMode) {
        if (state.value == mode) return
        state.value = mode
        store.putString(KEY_MODE, mode.name)
    }

    override fun fontScale(): Float = scaleState.value
    override fun fontScaleFlow(): Flow<Float> = scaleState.asStateFlow()
    override fun setFontScale(scale: Float) {
        val clamped = scale.coerceIn(FontScale.MIN, FontScale.MAX)
        if (scaleState.value == clamped) return
        scaleState.value = clamped
        store.putString(KEY_FONT_SCALE, clamped.toString())
    }

    override fun fontFamily(): AppFontFamily = familyState.value
    override fun fontFamilyFlow(): Flow<AppFontFamily> = familyState.asStateFlow()
    override fun setFontFamily(family: AppFontFamily) {
        if (familyState.value == family) return
        familyState.value = family
        store.putString(KEY_FONT_FAMILY, family.name)
    }

    fun uiZoom(): Float = zoomState.value
    fun uiZoomFlow(): Flow<Float> = zoomState.asStateFlow()
    fun setUiZoom(zoom: Float) {
        val clamped = zoom.coerceIn(UiZoom.MIN, UiZoom.MAX)
        if (zoomState.value == clamped) return
        zoomState.value = clamped
        store.putString(KEY_UI_ZOOM, clamped.toString())
    }

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_FONT_FAMILY = "font_family"
        const val KEY_UI_ZOOM = "ui_zoom"
    }
}
