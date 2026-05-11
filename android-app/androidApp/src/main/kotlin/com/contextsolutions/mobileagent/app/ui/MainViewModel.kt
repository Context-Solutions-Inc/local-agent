package com.contextsolutions.mobileagent.app.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contextsolutions.mobileagent.app.service.InferenceSessionManager
import com.contextsolutions.mobileagent.app.service.ModelDownloadController
import com.contextsolutions.mobileagent.app.service.ModelInventory
import com.contextsolutions.mobileagent.app.service.WarmUpOutcome
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
    private val sessionManager: InferenceSessionManager,
    onboardingPreferences: OnboardingPreferences,
    telemetryConsent: TelemetryConsentManager,
    controller: ModelDownloadController,
) : ViewModel() {

    val modelPresent: StateFlow<Boolean> = controller.state
        .map { inventory.isPresent() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, inventory.isPresent())

    /**
     * True once the user has completed every onboarding step (disclosure
     * + brave key + telemetry consent). M6 Phase E adds this gate before
     * the [modelPresent] check, so a brand-new install lands on
     * `OnboardingHost` rather than directly on the download screen.
     */
    val onboardingComplete: StateFlow<Boolean> = combine(
        onboardingPreferences.disclosureAcknowledgedFlow(),
        onboardingPreferences.braveKeyDecidedFlow(),
        telemetryConsent.firstRunDecidedFlow(),
    ) { disclosure, braveKey, telemetry ->
        disclosure && braveKey && telemetry
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        initialValue = onboardingPreferences.disclosureAcknowledged() &&
            onboardingPreferences.braveKeyDecided() &&
            telemetryConsent.firstRunDecided(),
    )

    /**
     * M6 Phase B — eager Gemma warm-up triggered when the user lands on the
     * Chat surface. Returns the outcome so the caller (MainScreen
     * `LaunchedEffect`) can log it. Suspend so the Compose scope owns
     * cancellation: a route change away from Chat while the load is in flight
     * cancels the call cleanly without leaving a dangling coroutine.
     *
     * The 300 ms debounce + still-on-Chat re-check live in the caller, not
     * here, so the ViewModel doesn't have to model navigation state. This
     * method is a pure side-effect: "if you can, please load the model now".
     */
    suspend fun warmUpEagerly(): WarmUpOutcome {
        // Defensive: routes flip to Chat only when modelPresent is true (see
        // MainScreen), but a race between WorkManager completion and the user
        // navigating into Chat could in theory call this before the file lands.
        // Return a benign outcome instead of trying to load a missing path.
        if (!inventory.isPresent()) {
            Log.i(TAG, "eager warm-up skipped: model not present")
            return WarmUpOutcome.Failed(IllegalStateException("model not present"))
        }
        val modelPath = inventory.localFile().absolutePath
        val outcome = sessionManager.warmUpIfPossible(modelPath)
        Log.i(TAG, "eager warm-up outcome: ${outcome.label()}")
        return outcome
    }

    private fun WarmUpOutcome.label(): String = when (this) {
        WarmUpOutcome.AlreadyLoaded -> "already_loaded"
        WarmUpOutcome.AlreadyLoading -> "already_loading"
        is WarmUpOutcome.SkippedThermal -> "skipped_thermal(${status.name})"
        is WarmUpOutcome.Loaded -> "loaded(${accelerator.name})"
        is WarmUpOutcome.Failed -> "failed(${cause::class.simpleName})"
    }

    private companion object {
        const val TAG = "EagerWarmUp"
    }
}
