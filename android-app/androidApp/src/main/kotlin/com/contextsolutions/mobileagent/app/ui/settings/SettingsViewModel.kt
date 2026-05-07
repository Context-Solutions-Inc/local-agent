package com.contextsolutions.mobileagent.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contextsolutions.mobileagent.app.BuildConfig
import com.contextsolutions.mobileagent.platform.SecureStorage
import com.contextsolutions.mobileagent.platform.SecureStorageKeys
import com.contextsolutions.mobileagent.search.SearchCacheDao
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backs the M2 settings screen: Brave key, search toggle, cache clear.
 *
 * The Brave key lives in [SecureStorage] (EncryptedSharedPreferences); this VM
 * never holds the key in memory beyond what the user is currently typing — the
 * UI mask + immediate save-and-discard pattern keeps the key out of the
 * Compose state tree.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val secureStorage: SecureStorage,
    private val cache: SearchCacheDao,
) : ViewModel() {

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun saveBraveKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            secureStorage.remove(SecureStorageKeys.BRAVE_API_KEY)
        } else {
            secureStorage.put(SecureStorageKeys.BRAVE_API_KEY, trimmed)
        }
        _state.update { it.copy(hasUserKey = trimmed.isNotEmpty(), keyJustSaved = true) }
    }

    fun clearBraveKey() {
        secureStorage.remove(SecureStorageKeys.BRAVE_API_KEY)
        _state.update { it.copy(hasUserKey = false, keyJustSaved = false) }
    }

    fun acknowledgeKeySaved() {
        _state.update { it.copy(keyJustSaved = false) }
    }

    fun setSearchEnabled(enabled: Boolean) {
        secureStorage.put(SecureStorageKeys.SEARCH_ENABLED, if (enabled) "true" else "false")
        _state.update { it.copy(searchEnabled = enabled) }
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            cache.clear()
            val count = cache.count()
            _state.update { it.copy(cacheCount = count, cacheJustCleared = true) }
        }
    }

    fun acknowledgeCacheCleared() {
        _state.update { it.copy(cacheJustCleared = false) }
    }

    private fun initialState(): SettingsUiState {
        val hasUser = secureStorage.contains(SecureStorageKeys.BRAVE_API_KEY) &&
            !secureStorage.get(SecureStorageKeys.BRAVE_API_KEY).isNullOrBlank()
        val searchEnabled = secureStorage.get(SecureStorageKeys.SEARCH_ENABLED) != "false"
        return SettingsUiState(
            hasUserKey = hasUser,
            hasDevKey = BuildConfig.INTERNAL_BUILD && BuildConfig.BRAVE_DEV_KEY.isNotBlank(),
            searchEnabled = searchEnabled,
            cacheCount = -1L,
        ).also {
            // Load cache count off the main thread.
            viewModelScope.launch(Dispatchers.IO) {
                val count = withContext(Dispatchers.IO) { cache.count() }
                _state.update { st -> st.copy(cacheCount = count) }
            }
        }
    }
}

data class SettingsUiState(
    val hasUserKey: Boolean,
    val hasDevKey: Boolean,
    val searchEnabled: Boolean,
    /** -1 = not yet loaded. */
    val cacheCount: Long,
    val keyJustSaved: Boolean = false,
    val cacheJustCleared: Boolean = false,
)
