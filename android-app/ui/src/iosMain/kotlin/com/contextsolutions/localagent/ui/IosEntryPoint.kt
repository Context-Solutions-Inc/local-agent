package com.contextsolutions.localagent.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import com.contextsolutions.localagent.di.agentCoreModule
import com.contextsolutions.localagent.di.iosModule
import com.contextsolutions.localagent.inference.IosAuxModelWarmer
import com.contextsolutions.localagent.inference.IosDownloadState
import com.contextsolutions.localagent.inference.IosModelDownloadController
import com.contextsolutions.localagent.inference.IosModelSpec
import com.contextsolutions.localagent.inference.NativeClassifierBridge
import com.contextsolutions.localagent.inference.NativeEmbedderBridge
import com.contextsolutions.localagent.inference.NativeLlmBridge
import com.contextsolutions.localagent.link.transport.NativeRelayBridge
import com.contextsolutions.localagent.platform.NativeQrScanner
import com.contextsolutions.localagent.sync.SyncController
import com.contextsolutions.localagent.i18n.StringCatalog
import com.contextsolutions.localagent.i18n.StringKeys
import com.contextsolutions.localagent.ui.i18n.tr
import com.contextsolutions.localagent.onboarding.OnboardingPreferences
import com.contextsolutions.localagent.ui.di.uiModule
import com.contextsolutions.localagent.ui.i18n.LocalStrings
import com.contextsolutions.localagent.ui.navigation.AppNavHost
import com.contextsolutions.localagent.ui.theme.AppThemeScaffold
import com.contextsolutions.localagent.ui.theme.DarkMonochromeColorScheme
import com.contextsolutions.localagent.ui.theme.LightMonochromeColorScheme
import com.contextsolutions.localagent.ui.theme.ThemeMode
import com.contextsolutions.localagent.ui.theme.ThemeModeViewModel
import androidx.compose.runtime.CompositionLocalProvider
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.context.startKoin
import platform.UIKit.UIViewController

/**
 * iOS entry point (PR #41), exported to Swift as `IosEntryPointKt`.
 *
 * [doInitKoin] starts the Koin graph with the Swift-provided on-device LLM
 * [bridge]; [MainViewController] hosts the shared Compose UI ([AppNavHost]) in a
 * `UIViewController` for the SwiftUI shell to embed.
 */
fun doInitKoin(
    llmBridge: NativeLlmBridge,
    classifierBridge: NativeClassifierBridge,
    embedderBridge: NativeEmbedderBridge,
    relayBridge: NativeRelayBridge,
    qrScanner: NativeQrScanner,
) {
    val app = startKoin {
        modules(
            agentCoreModule,
            iosModule(llmBridge, classifierBridge, embedderBridge, relayBridge, qrScanner),
            uiModule,
        )
    }
    // Start relay sync (foreground-only, like Android). SyncController.start() self-gates on the
    // link being configured, so it idles until a desktop is paired + the relay is up.
    app.koin.get<SyncController>().start()
}

fun MainViewController(): UIViewController = ComposeUIViewController {
    val strings by koinInject<StringCatalog>().active.collectAsState()
    val themeVm: ThemeModeViewModel = koinViewModel()
    val mode by themeVm.mode.collectAsState()
    val fontScale by themeVm.fontScale.collectAsState()
    val fontFamily by themeVm.fontFamily.collectAsState()

    // Monochrome (white/black/grey), shared with desktop — NOT the M3 purple baseline
    // (invariant #46). Auto follows the iOS system appearance: on iOS
    // `isSystemInDarkTheme()` is backed by UITraitCollection and updates live (unlike
    // Linux), so ThemeMode.System tracks Settings → Display & Brightness.
    val dark = when (mode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val colors: ColorScheme = if (dark) DarkMonochromeColorScheme else LightMonochromeColorScheme

    CompositionLocalProvider(LocalStrings provides strings) {
        AppThemeScaffold(colorScheme = colors, fontScale = fontScale, fontFamily = fontFamily) {
            val onboarding = koinInject<OnboardingPreferences>()
            val onboardingComplete by onboarding.languageDecidedFlow()
                .collectAsState(initial = onboarding.languageDecided())
            val downloads = koinInject<IosModelDownloadController>()
            val modelPresent by downloads.modelPresent.collectAsState()
            // Lazily fetch + warm the aux ONNX models once past the LLM download gate.
            // Feature-gated inside the warmer: embedder when memory is on (default),
            // classifier when web search is enabled. Toggling a feature on takes effect
            // on the next entry here. (Idempotent.)
            val auxWarmer = koinInject<IosAuxModelWarmer>()
            LaunchedEffect(modelPresent) { if (modelPresent) auxWarmer.kickIfEnabled() }
            AppNavHost(
                onboardingComplete = onboardingComplete,
                modelPresent = modelPresent,
                downloadContent = { IosDownloadScreen(downloads) },
            )
        }
    }
}

/**
 * Minimal first-run model-download gate for iOS — fetches the Gemma `.litertlm`
 * (~2.58 GB) with progress, auto-starting on first show and offering retry on
 * failure. Replaced by the chat UI once [IosModelDownloadController.modelPresent]
 * flips true.
 */
@Composable
private fun IosDownloadScreen(controller: IosModelDownloadController) {
    val state by controller.state.collectAsState()
    LaunchedEffect(Unit) { controller.start() }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(tr(StringKeys.DOWNLOAD_TITLE), style = MaterialTheme.typography.titleMedium)
        Text(
            tr(StringKeys.DOWNLOAD_INTRO),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )

        // List the actual model(s) being downloaded, like Android's DownloadScreen.
        // iOS downloads only Gemma (classifier/embedder are no-op this milestone).
        Text(tr(StringKeys.DOWNLOAD_MODELS_HEADER), style = MaterialTheme.typography.bodySmall)
        Text(
            "• ${IosModelSpec.FILENAME} — ${formatBytes(IosModelSpec.SIZE_BYTES)}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )

        when (val s = state) {
            is IosDownloadState.Idle -> CircularProgressIndicator()
            is IosDownloadState.Downloading -> {
                LinearProgressIndicator(progress = { s.fraction }, modifier = Modifier.fillMaxWidth())
                val downloaded = (s.fraction.toDouble() * IosModelSpec.SIZE_BYTES).toLong()
                Text(
                    tr(
                        StringKeys.DOWNLOAD_PROGRESS,
                        (s.fraction * 100).toInt(),
                        formatBytes(downloaded),
                        formatBytes(IosModelSpec.SIZE_BYTES),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            is IosDownloadState.Done -> CircularProgressIndicator()
            is IosDownloadState.Failed -> {
                Text("Download failed: ${s.message}", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { controller.retry() }) { Text("Retry") }
            }
        }
    }
}

/** Decimal (SI, 1000-based) byte formatter — matches Android's `formatShortFileSize`. */
private fun formatBytes(bytes: Long): String {
    if (bytes >= 1_000_000_000L) {
        val hundredths = (bytes * 100 / 1_000_000_000L)
        return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')} GB"
    }
    if (bytes >= 1_000_000L) {
        val tenths = (bytes * 10 / 1_000_000L)
        return "${tenths / 10}.${tenths % 10} MB"
    }
    return "${bytes / 1000L} KB"
}
