package com.contextsolutions.mobileagent.app.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * M6 Phase E — first-run onboarding host. Renders the appropriate step
 * Composable based on [OnboardingViewModel.step]; calls [onComplete]
 * when the ViewModel reaches [OnboardingStep.Complete] so `MainScreen`
 * can route to the download / chat flow.
 *
 * The host is intentionally thin — every step's persistence happens
 * through the ViewModel, which means the user can kill the app between
 * screens and resume exactly where they left off on next launch.
 */
@Composable
fun OnboardingHost(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val step by viewModel.step.collectAsState()

    LaunchedEffect(step) {
        if (step is OnboardingStep.Complete) onComplete()
    }

    when (step) {
        OnboardingStep.Disclosure -> DisclosureScreen(
            onContinue = viewModel::acknowledgeDisclosure,
        )
        OnboardingStep.BraveKey -> BraveKeyScreen(
            onSave = viewModel::saveBraveKey,
            onSkip = viewModel::skipBraveKey,
        )
        OnboardingStep.TelemetryConsent -> TelemetryConsentScreen(
            onAccept = { viewModel.setTelemetryConsent(true) },
            onDecline = { viewModel.setTelemetryConsent(false) },
        )
        OnboardingStep.Complete -> {
            // No-op — LaunchedEffect above routes us out of the host.
        }
    }
}
