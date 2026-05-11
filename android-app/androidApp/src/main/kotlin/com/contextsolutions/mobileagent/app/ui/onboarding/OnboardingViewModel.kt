package com.contextsolutions.mobileagent.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contextsolutions.mobileagent.onboarding.OnboardingPreferences
import com.contextsolutions.mobileagent.platform.SecureStorage
import com.contextsolutions.mobileagent.platform.SecureStorageKeys
import com.contextsolutions.mobileagent.telemetry.TelemetryConsentManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Drives the first-run onboarding flow (M6 Phase E, PRD §6.1).
 *
 * Step state is derived from four persisted booleans:
 *  - [OnboardingPreferences.disclosureAcknowledged]
 *  - [OnboardingPreferences.braveKeyDecided]
 *  - [OnboardingPreferences.hfAuthTokenDecided]
 *  - [TelemetryConsentManager.firstRunDecided]
 *
 * The first one that isn't true determines the active step. When all
 * four are true, the host emits [OnboardingStep.Complete] and
 * `MainScreen` routes away from `OnboardingHost`.
 *
 * Brave key + HF token entries are wired straight to [SecureStorage] (same
 * paths the corresponding `SettingsViewModel` calls use); the host doesn't
 * need to coordinate with SettingsViewModel for this.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val onboardingPreferences: OnboardingPreferences,
    private val telemetryConsent: TelemetryConsentManager,
    private val secureStorage: SecureStorage,
) : ViewModel() {

    private val _step = MutableStateFlow(currentStep())
    val step: StateFlow<OnboardingStep> = _step.asStateFlow()

    init {
        // Re-derive the step whenever any underlying flag changes — covers
        // the case where the consent screen advances the telemetry flag,
        // for instance.
        combine(
            onboardingPreferences.disclosureAcknowledgedFlow(),
            onboardingPreferences.braveKeyDecidedFlow(),
            onboardingPreferences.hfAuthTokenDecidedFlow(),
            telemetryConsent.firstRunDecidedFlow(),
        ) { _, _, _, _ -> currentStep() }
            .onEach { _step.value = it }
            .launchIn(viewModelScope)
    }

    fun acknowledgeDisclosure() {
        onboardingPreferences.markDisclosureAcknowledged()
    }

    /** Save the user's Brave Search key + mark the step decided. */
    fun saveBraveKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isNotEmpty()) {
            secureStorage.put(SecureStorageKeys.BRAVE_API_KEY, trimmed)
        }
        onboardingPreferences.markBraveKeyDecided()
    }

    /** Skip the Brave key entry — user can add one later from Settings. */
    fun skipBraveKey() {
        onboardingPreferences.markBraveKeyDecided()
    }

    /** Save the user's HuggingFace API token + mark the step decided. */
    fun saveHfAuthToken(token: String) {
        val trimmed = token.trim()
        if (trimmed.isNotEmpty()) {
            secureStorage.put(SecureStorageKeys.HF_AUTH_TOKEN, trimmed)
        }
        onboardingPreferences.markHfAuthTokenDecided()
    }

    /** Skip the HF token entry — user can add one later from Settings. */
    fun skipHfAuthToken() {
        onboardingPreferences.markHfAuthTokenDecided()
    }

    fun setTelemetryConsent(enabled: Boolean) {
        telemetryConsent.setEnabled(enabled)
        telemetryConsent.markFirstRunDecided()
    }

    private fun currentStep(): OnboardingStep = when {
        !onboardingPreferences.disclosureAcknowledged() -> OnboardingStep.Disclosure
        !onboardingPreferences.braveKeyDecided() -> OnboardingStep.BraveKey
        !onboardingPreferences.hfAuthTokenDecided() -> OnboardingStep.HfAuthToken
        !telemetryConsent.firstRunDecided() -> OnboardingStep.TelemetryConsent
        else -> OnboardingStep.Complete
    }
}

/** Current step in the onboarding flow. [Complete] is the terminal state. */
sealed interface OnboardingStep {
    data object Disclosure : OnboardingStep
    data object BraveKey : OnboardingStep
    data object HfAuthToken : OnboardingStep
    data object TelemetryConsent : OnboardingStep
    data object Complete : OnboardingStep
}
