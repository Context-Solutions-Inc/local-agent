package com.contextsolutions.mobileagent.app.ui.theme

import android.content.Context
import com.contextsolutions.mobileagent.ui.theme.ThemeMode
import com.contextsolutions.mobileagent.ui.theme.ThemePreferences
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
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_MODE, ThemeMode.System.name)!!) }
            .getOrDefault(ThemeMode.System),
    )

    override fun themeMode(): ThemeMode = state.value
    override fun themeModeFlow(): Flow<ThemeMode> = state.asStateFlow()
    override fun setThemeMode(mode: ThemeMode) {
        if (state.value == mode) return
        state.value = mode
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    private companion object {
        const val PREFS_NAME = "theme"
        const val KEY_MODE = "mode"
    }
}
