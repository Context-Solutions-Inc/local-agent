package com.contextsolutions.localagent.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contextsolutions.localagent.language.LanguagePreferences
import com.contextsolutions.localagent.language.PreferredLanguage
import com.contextsolutions.localagent.onboarding.OnboardingPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * Drives the first-run onboarding flow (M6 Phase E, PRD §6.1).
 *
 * Step state is derived from a single persisted boolean:
 *  - [OnboardingPreferences.languageDecided]
 *
 * When it is true, the host emits [OnboardingStep.Complete] and `MainScreen`
 * routes away from `OnboardingHost`.
 *
 * PR #22 dropped the Brave-key, HuggingFace-token, and telemetry-consent
 * steps; PR #23 dropped the country/location step; PR #31 dropped the
 * on-device privacy-disclosure step (its message moved into shorter,
 * in-context copy in Settings + the chat empty state), leaving language as
 * the only gate. The search-defaults **country defaults to USA** (the
 * repositories fall back to `US` when no location is saved) and is
 * changeable in Settings → Search sources.
 */
class OnboardingViewModel(
    private val onboardingPreferences: OnboardingPreferences,
    private val languagePreferences: LanguagePreferences,
) : ViewModel() {

    private val _step = MutableStateFlow(currentStep())
    val step: StateFlow<OnboardingStep> = _step.asStateFlow()

    /**
     * The live language selection on the first onboarding screen. Reading it as
     * a flow lets the picker highlight the choice; writes go through
     * [selectLanguage], which updates `LanguagePreferences` immediately so the
     * whole onboarding tree re-renders in the chosen language as a live preview.
     */
    val language: StateFlow<PreferredLanguage> = languagePreferences.preferredLanguageFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, languagePreferences.preferredLanguage())

    init {
        // Re-derive the step whenever the language gate changes.
        onboardingPreferences.languageDecidedFlow()
            .onEach { _step.value = currentStep() }
            .launchIn(viewModelScope)
    }

    /**
     * Live language preview — persists the choice through [LanguagePreferences]
     * so the catalog (and thus every onboarding screen) switches immediately,
     * WITHOUT advancing the step. The user confirms with [confirmLanguage].
     */
    fun selectLanguage(language: PreferredLanguage) {
        languagePreferences.setPreferredLanguage(language)
    }

    /** Accept the (already-applied) language choice and advance onboarding. */
    fun confirmLanguage() {
        onboardingPreferences.markLanguageDecided()
    }

    private fun currentStep(): OnboardingStep =
        if (!onboardingPreferences.languageDecided()) OnboardingStep.Language
        else OnboardingStep.Complete
}

/** Current step in the onboarding flow. [Complete] is the terminal state. */
sealed interface OnboardingStep {
    data object Language : OnboardingStep
    data object Complete : OnboardingStep
}
