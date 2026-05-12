package com.contextsolutions.mobileagent.app.ui.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class ThemeModeViewModel @Inject constructor(
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
