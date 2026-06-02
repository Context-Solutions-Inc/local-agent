package com.contextsolutions.mobileagent.ui.theme

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

    fun cycle() {
        preferences.setThemeMode(preferences.themeMode().next())
    }

    fun setMode(mode: ThemeMode) {
        preferences.setThemeMode(mode)
    }
}
