package com.contextsolutions.mobileagent.app.spike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import com.contextsolutions.mobileagent.app.ui.theme.MobileAgentTheme
import dagger.hilt.android.AndroidEntryPoint
import java.io.File

/**
 * UI surface for running the M0 inference spike on a Pixel 7.
 *
 * Internal-only — release builds disable this Activity via PackageManager
 * (wired in M6 release engineering).
 */
@AndroidEntryPoint
class SpikeActivity : ComponentActivity() {

    private val viewModel: SpikeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MobileAgentTheme {
                SpikeScreen(viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpikeScreen(viewModel: SpikeViewModel) {
    val state by viewModel.state.collectAsState()
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val modelFile = remember(context) {
        File(context.getExternalFilesDir("models"), MODEL_FILENAME)
    }
    val modelPresent = remember(modelFile, state) { modelFile.exists() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("M0 Inference Spike") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState),
        ) {
            Text(
                text = "Pixel 7 + Android 16 — Gemma 4 E2B (litert-community)",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Model expected at:\n${modelFile.absolutePath}\n" +
                    if (modelPresent) "✓ Found (${modelFile.length() / 1024 / 1024} MB)"
                    else "✗ Not found — push it with:\n" +
                        "adb push gemma-4-E2B-it.litertlm \\\n" +
                        "  /sdcard/Android/data/${context.packageName}/files/models/",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    viewModel.runBenchmark(
                        modelPath = modelFile.absolutePath,
                        accelerator = DEFAULT_ACCELERATOR,
                    )
                },
                enabled = state !is SpikeUiState.InProgress && modelPresent,
            ) {
                Text("Run benchmark")
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { viewModel.clearResults() }) {
                Text("Clear results")
            }
            Spacer(Modifier.height(16.dp))

            when (val s = state) {
                is SpikeUiState.Idle -> Text("Idle.")
                is SpikeUiState.InProgress -> Text(s.message)
                is SpikeUiState.Complete -> ResultsView(s.run)
            }
        }
    }
}

@Composable
private fun ResultsView(run: SpikeRun) {
    val summary = remember(run) { run.summary() }
    Text(
        text = buildString {
            appendLine("Run: ${run.runId}")
            appendLine("Device: ${run.device}")
            appendLine("Android: ${run.androidVersion}")
            appendLine("Accelerator: ${run.accelerator}")
            appendLine("Model: ${run.modelPath}")
            appendLine("KV cache tokens: ${run.kvCacheTokens}")
            appendLine()
            appendLine("=== Summary ===")
            appendLine("First token p50: ${summary.firstTokenLatencyP50Ms} ms")
            appendLine("First token p95: ${summary.firstTokenLatencyP95Ms} ms")
            appendLine("Sustained tok/s mean: %.2f".format(summary.sustainedTokensPerSecondMean))
            appendLine("Peak RSS: %.1f MB".format(summary.peakRssBytes / 1024.0 / 1024.0))
            appendLine("Prompts: ${summary.promptsRun}")
            appendLine()
            appendLine("=== Per-prompt ===")
            run.results.forEach { r ->
                appendLine("- ${r.promptId}")
                appendLine("    first token: ${r.firstTokenLatencyMs} ms")
                appendLine("    total tokens: ${r.totalTokens} in ${r.totalGenerationMs} ms (%.2f tok/s)".format(r.sustainedTokensPerSecond))
                appendLine("    peak RSS: %.1f MB | thermal max: ${r.thermalStateMaxObserved}".format(r.peakRssBytes / 1024.0 / 1024.0))
                if (r.errorMessage != null) appendLine("    ERROR: ${r.errorMessage}")
            }
            appendLine()
            appendLine("Results JSON written to filesDir/spike-results/spike-${run.runId}.json")
            appendLine("Pull with: adb shell run-as com.contextsolutions.mobileagent.debug cat files/spike-results/spike-${run.runId}.json")
        },
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
    )
}

// Model file expected under context.getExternalFilesDir("models") so it shows up at
// /sdcard/Android/data/<applicationId>/files/models/<MODEL_FILENAME> for adb push.
// From: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
private const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"

// AUTO maps to GPU (Mali-G710) per LiteRtInferenceEngine.resolveBackend. Run the
// spike with each accelerator (NPU, GPU, CPU) explicitly to fill in the M0 memo.
private const val DEFAULT_ACCELERATOR = "AUTO"
