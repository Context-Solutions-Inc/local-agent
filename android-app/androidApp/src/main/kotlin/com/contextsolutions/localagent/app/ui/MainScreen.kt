package com.contextsolutions.localagent.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.contextsolutions.localagent.app.ui.download.DownloadScreen
import com.contextsolutions.localagent.ui.navigation.AppNavHost
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * Android shell wrapper around the shared [AppNavHost] (Phase 9 inc 8d). All
 * screens + the route graph now live in `:ui`; this supplies the Android gating
 * state ([MainViewModel] — onboarding + model-present), the Android-only
 * WorkManager [DownloadScreen] slot, and the aux-engine warm-up effect.
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel = koinViewModel(),
) {
    val modelPresent by viewModel.modelPresent.collectAsState()
    val onboardingComplete by viewModel.onboardingComplete.collectAsState()
    AppNavHost(
        onboardingComplete = onboardingComplete,
        modelPresent = modelPresent,
        downloadContent = { DownloadScreen() },
        chatWarmUp = { ChatWarmUpEffect(viewModel) },
    )
}

/**
 * Eager aux (classifier + embedder) warm-up while the Chat surface is visible —
 * rendered only inside [AppNavHost]'s Chat branch, so entering/leaving Chat
 * composes/decomposes it. Gemma is NOT warmed here (PR #25): it loads on the
 * first generate() call.
 *
 * [LifecycleResumeEffect] (invariant #22, NOT LaunchedEffect) re-runs on
 * background→foreground so a 5-min idle / onTrimMemory unload that happened while
 * backgrounded gets re-warmed on resume. The 300 ms debounce catches short
 * Settings → Chat flips and quick background bounces; the launched coroutine is
 * cancelled via onPauseOrDispose if Chat leaves or the Activity pauses.
 */
@Composable
private fun ChatWarmUpEffect(viewModel: MainViewModel) {
    val warmUpScope = rememberCoroutineScope()
    LifecycleResumeEffect(Unit) {
        val warmUpJob: Job = warmUpScope.launch {
            delay(EAGER_WARMUP_DEBOUNCE_MS)
            viewModel.warmUpAuxEngines()
        }
        onPauseOrDispose { warmUpJob.cancel() }
    }
}

/**
 * Debounce window between landing on Chat and kicking off the aux-engine
 * warm-up. 300 ms catches Settings → Chat → Settings flips (warm-up cancelled
 * before any work happens) without delaying intentional Chat entry noticeably.
 */
private const val EAGER_WARMUP_DEBOUNCE_MS = 300L
