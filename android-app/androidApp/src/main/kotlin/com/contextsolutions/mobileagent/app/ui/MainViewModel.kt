package com.contextsolutions.mobileagent.app.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contextsolutions.mobileagent.app.service.AuxModelLifecycleCoordinator
import com.contextsolutions.mobileagent.app.service.ModelDownloadController
import com.contextsolutions.mobileagent.app.service.ModelInventory
import com.contextsolutions.mobileagent.onboarding.OnboardingPreferences
import com.contextsolutions.mobileagent.telemetry.TelemetryConsentManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Top-level routing decision: download flow or chat surface.
 *
 * The routing depends on (a) whether the model file is on disk, and (b) the
 * current download-controller state. We re-check (a) on every emission of (b),
 * which is cheap (a single file existence + size check) and gives us free
 * reactivity — when the Worker renames the partial to the final filename and
 * the controller emits Completed, our flow re-runs and isPresent flips to true.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val inventory: ModelInventory,
    private val auxModelCoordinator: AuxModelLifecycleCoordinator,
    onboardingPreferences: OnboardingPreferences,
    telemetryConsent: TelemetryConsentManager,
    controller: ModelDownloadController,
) : ViewModel() {

    val modelPresent: StateFlow<Boolean> = controller.state
        .map { inventory.isPresent() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, inventory.isPresent())

    /**
     * True once the user has completed every onboarding step (disclosure
     * + brave key + HF auth token + telemetry consent). M6 Phase E added
     * this gate before the [modelPresent] check, so a brand-new install
     * lands on `OnboardingHost` rather than directly on the download
     * screen.
     */
    val onboardingComplete: StateFlow<Boolean> = combine(
        onboardingPreferences.disclosureAcknowledgedFlow(),
        onboardingPreferences.braveKeyDecidedFlow(),
        onboardingPreferences.hfAuthTokenDecidedFlow(),
        telemetryConsent.firstRunDecidedFlow(),
    ) { disclosure, braveKey, hfToken, telemetry ->
        disclosure && braveKey && hfToken && telemetry
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        initialValue = onboardingPreferences.disclosureAcknowledged() &&
            onboardingPreferences.braveKeyDecided() &&
            onboardingPreferences.hfAuthTokenDecided() &&
            telemetryConsent.firstRunDecided(),
    )

    /**
     * Eager warm-up of the aux (classifier + embedder) engines when the user
     * lands on the Chat surface. Gemma is NOT warmed eagerly (PR #25): it
     * loads on the first `InferenceSessionManager.generate()` call, which
     * happens only on fall-through queries — regex tools (clock/todo/memory)
     * and classifier-routed verticals like weather never touch the LLM.
     *
     * The classifier (~113 ms warm-up, 67 MB) and embedder (~41 ms, 23 MB)
     * run on every non-regex turn for pre-flight + memory retrieval, so they
     * stay on the same RESUME hook that fires when (a) the route flips to
     * Chat from a sibling screen or (b) the activity returns from background
     * after a 5-min idle unload / onTrimMemory.
     *
     * The 300 ms debounce + still-on-Chat re-check live in the caller. This
     * method is a pure side-effect: "if you can, warm the aux engines now".
     */
    suspend fun warmUpAuxEngines() {
        // Defensive: routes flip to Chat only when modelPresent is true (see
        // MainScreen), but a race between WorkManager completion and the user
        // navigating into Chat could in theory call this before the file lands.
        // Aux engines don't depend on the Gemma artifact, but we keep the
        // gate so the chat surface is the only entry point that warms them.
        if (!inventory.isPresent()) {
            Log.i(TAG, "aux warm-up skipped: model not present")
            return
        }
        runCatching { auxModelCoordinator.warmUpAll() }
            .onFailure { Log.w(TAG, "aux-model warm-up threw; will retry on first use", it) }
    }

    private companion object {
        const val TAG = "EagerWarmUp"
    }
}
