package com.contextsolutions.localagent.app.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contextsolutions.localagent.app.AppDiag
import com.contextsolutions.localagent.app.service.AuxModelLifecycleCoordinator
import com.contextsolutions.localagent.app.service.ModelDownloadController
import com.contextsolutions.localagent.app.service.ModelInventory
import com.contextsolutions.localagent.onboarding.OnboardingPreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
class MainViewModel(
    private val inventory: ModelInventory,
    private val auxModelCoordinator: AuxModelLifecycleCoordinator,
    onboardingPreferences: OnboardingPreferences,
    controller: ModelDownloadController,
) : ViewModel() {

    // PR #3 — gate on ALL required models (Gemma LLM + classifier + embedder, now
    // CDN-downloaded rather than bundled), so chat unlocks fully-capable: search
    // + memory need the aux models, which used to ship in the APK.
    val modelPresent: StateFlow<Boolean> = controller.state
        .map { inventory.allRequiredPresent() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, inventory.allRequiredPresent())

    /**
     * True once the user has completed onboarding. M6 Phase E added this gate
     * before the [modelPresent] check, so a brand-new install lands on
     * `OnboardingHost` rather than directly on the download screen. PR #22
     * removed the Brave-key, HF-token, and telemetry-consent steps; PR #23
     * removed the country/location step; PR #31 removed the privacy-disclosure
     * step — language is now the only gate (matches
     * `OnboardingViewModel.currentStep` == Complete).
     */
    val onboardingComplete: StateFlow<Boolean> =
        onboardingPreferences.languageDecidedFlow().stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            initialValue = onboardingPreferences.languageDecided(),
        )

    /**
     * Eager warm-up of the aux (classifier + embedder) engines when the user
     * lands on the Chat surface. Gemma is NOT warmed eagerly (PR #25): it
     * loads on the first `InferenceSessionManager.generate()` call, which
     * happens only on fall-through queries — regex tools (clock/my-list/memory)
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
        // Defensive: routes flip to Chat only when all models are present (see
        // MainScreen), but a race between WorkManager completion and the user
        // navigating into Chat could in theory call this before the aux files
        // land. Gate on the aux models specifically — they're what warmUpAll
        // loads (Gemma is warmed lazily on first generate(), PR #25).
        if (!inventory.auxModelsPresent()) {
            AppDiag.i(TAG, "aux warm-up skipped: aux models not present")
            return
        }
        runCatching { auxModelCoordinator.warmUpAll() }
            .onFailure { Log.w(TAG, "aux-model warm-up threw; will retry on first use", it) }
    }

    private companion object {
        const val TAG = "EagerWarmUp"
    }
}
