package com.contextsolutions.localagent.app.ui.theme

import android.content.Context
import com.contextsolutions.localagent.ui.theme.AppFontFamily
import com.contextsolutions.localagent.ui.theme.FontScale
import com.contextsolutions.localagent.ui.theme.ThemeMode
import com.contextsolutions.localagent.ui.theme.ThemePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android [ThemePreferences] actual — `SharedPreferences`-backed. The
 * `ThemeMode` enum + the interface live in shared `:ui` (Phase 9); this Android
 * impl stays in `:androidApp` (it needs a `Context`) and is bound in
 * `androidModule`. Desktop binds a JSON-backed actual instead.
 */
class SharedPreferencesThemePreferences(context: Context) : ThemePreferences {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_MODE, ThemeMode.Dark.name)!!) }
            .getOrDefault(ThemeMode.Dark),
    )

    private val scaleState = MutableStateFlow(
        prefs.getFloat(KEY_FONT_SCALE, FontScale.DEFAULT)
            .coerceIn(FontScale.MIN, FontScale.MAX),
    )

    private val familyState = MutableStateFlow(
        runCatching { AppFontFamily.valueOf(prefs.getString(KEY_FONT_FAMILY, AppFontFamily.System.name)!!) }
            .getOrDefault(AppFontFamily.System),
    )

    override fun themeMode(): ThemeMode = state.value
    override fun themeModeFlow(): Flow<ThemeMode> = state.asStateFlow()
    override fun setThemeMode(mode: ThemeMode) {
        if (state.value == mode) return
        state.value = mode
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    override fun fontScale(): Float = scaleState.value
    override fun fontScaleFlow(): Flow<Float> = scaleState.asStateFlow()
    override fun setFontScale(scale: Float) {
        val clamped = scale.coerceIn(FontScale.MIN, FontScale.MAX)
        if (scaleState.value == clamped) return
        scaleState.value = clamped
        prefs.edit().putFloat(KEY_FONT_SCALE, clamped).apply()
    }

    override fun fontFamily(): AppFontFamily = familyState.value
    override fun fontFamilyFlow(): Flow<AppFontFamily> = familyState.asStateFlow()
    override fun setFontFamily(family: AppFontFamily) {
        if (familyState.value == family) return
        familyState.value = family
        prefs.edit().putString(KEY_FONT_FAMILY, family.name).apply()
    }

    private companion object {
        const val PREFS_NAME = "theme"
        const val KEY_MODE = "mode"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_FONT_FAMILY = "font_family"
    }
}
