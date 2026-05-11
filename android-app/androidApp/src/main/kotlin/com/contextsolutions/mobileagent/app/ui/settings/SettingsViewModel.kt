package com.contextsolutions.mobileagent.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.contextsolutions.mobileagent.app.BuildConfig
import com.contextsolutions.mobileagent.app.service.TelemetryUploadWorker
import com.contextsolutions.mobileagent.observability.SafeCrashReporter
import com.contextsolutions.mobileagent.platform.SecureStorage
import com.contextsolutions.mobileagent.platform.SecureStorageKeys
import com.contextsolutions.mobileagent.search.SearchCacheDao
import com.contextsolutions.mobileagent.telemetry.TelemetryConsentManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
    @ApplicationContext private val appContext: Context,
    private val secureStorage: SecureStorage,
    private val cache: SearchCacheDao,
    private val telemetryConsent: TelemetryConsentManager,
    private val crashReporter: SafeCrashReporter,
) : ViewModel() {

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        // Mirror the consent toggle into UI state. Phase E onboarding's
        // consent screen writes via the same TelemetryConsentManager;
        // observing the flow keeps this Settings surface in sync without
        // a manual refresh on return-to-Settings.
        telemetryConsent.enabledFlow()
            .onEach { enabled -> _state.update { it.copy(telemetryEnabled = enabled) } }
            .launchIn(viewModelScope)
    }

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

    fun setTelemetryEnabled(enabled: Boolean) {
        telemetryConsent.setEnabled(enabled)
        // The Application-level Flow observer (MobileAgentApplication) also
        // toggles FirebaseAnalytics.setAnalyticsCollectionEnabled in response.
    }

    /**
     * Debug-only — bypass the 24h periodic schedule and fire one telemetry
     * upload immediately. The button on [SettingsScreen] that calls this
     * is gated behind `BuildConfig.DEBUG`; the uploader itself still
     * checks consent, so this never sends data when the user is opted out.
     * Outcome appears in `logcat -s TelemetryWorker:I`.
     */
    fun triggerTelemetryUploadNow() {
        TelemetryUploadWorker.runNow(appContext)
    }

    /**
     * Debug-only — record a non-fatal exception that contains a leak
     * marker string in its message. Verifies that
     * [SafeCrashReporter.recordException] runs the throwable through
     * [com.contextsolutions.mobileagent.observability.ContentRedactor]
     * before forwarding to Crashlytics. The leaked Crashlytics dashboard
     * entry should show the redacted form (`Bearer <redacted>`), NOT
     * the raw token. The user must be opted in for this to surface;
     * Crashlytics collection is gated by the consent toggle.
     */
    fun triggerCrashRedactionTest() {
        crashReporter.recordException(
            RuntimeException(
                "telemetry leak test — Authorization: Bearer test_secret_12345 should be redacted",
            ),
        )
        // Force-flush so the report ships immediately. Without this,
        // Crashlytics queues non-fatals until the next app launch —
        // nothing appears in the dashboard for the developer's
        // verification flow.
        crashReporter.flushPending()
    }

    /**
     * Debug-only — record a breadcrumb that contains a leak marker.
     * Same redaction guarantee as [triggerCrashRedactionTest]: the
     * breadcrumb should appear scrubbed in the dashboard.
     *
     * Note: breadcrumbs ([SafeCrashReporter.log]) only appear in the
     * dashboard ATTACHED TO a recorded exception. To verify breadcrumb
     * redaction end-to-end, tap this button then immediately tap "Test
     * crash redaction" — the breadcrumb will appear in that crash's
     * Logs tab.
     */
    fun triggerBreadcrumbRedactionTest() {
        crashReporter.log(
            "breadcrumb leak test — X-Subscription-Token: BSA-test-key-12345 should be redacted",
        )
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
            telemetryEnabled = telemetryConsent.enabled(),
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
    val telemetryEnabled: Boolean = false,
    val keyJustSaved: Boolean = false,
    val cacheJustCleared: Boolean = false,
)
