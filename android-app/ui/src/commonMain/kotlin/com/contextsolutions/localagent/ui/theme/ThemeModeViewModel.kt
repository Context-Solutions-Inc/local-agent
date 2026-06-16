package com.contextsolutions.localagent.ui.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class ThemeModeViewModel(
    private val preferences: ThemePreferences,
) : ViewModel() {

    val mode: StateFlow<ThemeMode> = preferences.themeModeFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, preferences.themeMode())

    val fontScale: StateFlow<Float> = preferences.fontScaleFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, preferences.fontScale())

    val fontFamily: StateFlow<AppFontFamily> = preferences.fontFamilyFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, preferences.fontFamily())

    fun cycle() {
        preferences.setThemeMode(preferences.themeMode().next())
    }

    fun setMode(mode: ThemeMode) {
        preferences.setThemeMode(mode)
    }

    fun setFontScale(scale: Float) {
        preferences.setFontScale(scale)
    }

    fun setFontFamily(family: AppFontFamily) {
        preferences.setFontFamily(family)
    }
}
