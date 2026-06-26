package com.contextsolutions.localagent.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contextsolutions.localagent.language.LanguagePreferences
import com.contextsolutions.localagent.language.PreferredLanguage
import com.contextsolutions.localagent.onboarding.OnboardingPreferences
import com.contextsolutions.localagent.preferences.LocationCatalog
import com.contextsolutions.localagent.preferences.SearchPreferencesRepository
import com.contextsolutions.localagent.preferences.UserLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the first-run onboarding flow (M6 Phase E, PRD §6.1; PR #23 adds
 * the Location step).
 *
 * Step state is derived from three persisted booleans (the language gate is
 * first so the rest of onboarding renders in the chosen language):
 *  - [OnboardingPreferences.languageDecided]
 *  - [OnboardingPreferences.disclosureAcknowledged]
 *  - [OnboardingPreferences.locationDecided]
 *
 * The first one that isn't true determines the active step. When all
 * three are true, the host emits [OnboardingStep.Complete] and
 * `MainScreen` routes away from `OnboardingHost`.
 *
 * PR #22 dropped the Brave-key, HuggingFace-token, and telemetry-consent
 * onboarding steps: web search + telemetry are now opt-in from Settings, and
 * model downloads no longer need an HF token (all models ship from the public
 * CDN). Location is written to [SearchPreferencesRepository] which auto-seeds
 * per-vertical default sources from the country code.
 */
class OnboardingViewModel(
    private val onboardingPreferences: OnboardingPreferences,
    private val searchPreferences: SearchPreferencesRepository,
    private val languagePreferences: LanguagePreferences,
    val locationCatalog: LocationCatalog,
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
        // Re-derive the step whenever any underlying flag changes.
        combine(
            onboardingPreferences.languageDecidedFlow(),
            onboardingPreferences.disclosureAcknowledgedFlow(),
            onboardingPreferences.locationDecidedFlow(),
        ) { _, _, _ -> currentStep() }
            .onEach { _step.value = it }
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

    fun acknowledgeDisclosure() {
        onboardingPreferences.markDisclosureAcknowledged()
    }

    /**
     * Persist the chosen [country] and mark the location step decided. The
     * repo auto-seeds the per-vertical default source lists for the country so
     * the user can immediately ask weather/news/sports/finance questions
     * without visiting Settings. Region/city are left empty — the weather path
     * asks for the specific city + state/province at query time (PR #37).
     */
    fun saveLocation(country: String) {
        viewModelScope.launch {
            searchPreferences.setLocation(
                UserLocation(country = country, regionCode = "", city = ""),
            )
            onboardingPreferences.markLocationDecided()
        }
    }

    /**
     * Skip the location step — seed defaults for the first country in the
     * catalog (US) so vertical adapters still have something to work with.
     * The user can correct it later in Settings.
     */
    fun skipLocation() {
        viewModelScope.launch {
            val fallback = locationCatalog.countries().firstOrNull()
            if (fallback != null) {
                searchPreferences.setLocation(
                    UserLocation(country = fallback.code, regionCode = "", city = ""),
                )
            }
            onboardingPreferences.markLocationDecided()
        }
    }

    private fun currentStep(): OnboardingStep = when {
        !onboardingPreferences.languageDecided() -> OnboardingStep.Language
        !onboardingPreferences.disclosureAcknowledged() -> OnboardingStep.Disclosure
        !onboardingPreferences.locationDecided() -> OnboardingStep.Location
        else -> OnboardingStep.Complete
    }
}

/** Current step in the onboarding flow. [Complete] is the terminal state. */
sealed interface OnboardingStep {
    data object Language : OnboardingStep
    data object Disclosure : OnboardingStep
    data object Location : OnboardingStep
    data object Complete : OnboardingStep
}
