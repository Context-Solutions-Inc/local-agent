package com.contextsolutions.mobileagent.app.spike

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.contextsolutions.mobileagent.inference.InferenceEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SpikeViewModel @Inject constructor(
    application: Application,
    private val engine: InferenceEngine,
) : AndroidViewModel(application) {

    private val runner = SpikeRunner(application, engine)

    private val _state = MutableStateFlow<SpikeUiState>(SpikeUiState.Idle)
    val state: StateFlow<SpikeUiState> = _state.asStateFlow()

    fun runBenchmark(modelPath: String, accelerator: String) {
        // Dispatchers.IO so the synchronous parts of SpikeRunner (memory snapshots,
        // engine.unload(), writing the JSON result) don't touch the main thread.
        // LiteRtInferenceEngine internally pins its native calls to IO too, but
        // launching here on IO removes any chance of an ANR from the harness itself.
        // _state is a MutableStateFlow → safe to update from any thread.
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = SpikeUiState.InProgress("Loading model…")
            val run = runner.run(
                modelPath = modelPath,
                accelerator = accelerator,
                onProgress = { progress ->
                    _state.value = when (progress) {
                        SpikeProgress.Loading -> SpikeUiState.InProgress("Loading model…")
                        is SpikeProgress.Generating ->
                            SpikeUiState.InProgress("Prompt ${progress.promptIndex + 1} / ${progress.total}…")
                        is SpikeProgress.Done -> SpikeUiState.Complete(progress.run)
                    }
                },
            )
            _state.value = SpikeUiState.Complete(run)
        }
    }

    fun clearResults() {
        _state.value = SpikeUiState.Idle
    }
}

sealed interface SpikeUiState {
    object Idle : SpikeUiState
    data class InProgress(val message: String) : SpikeUiState
    data class Complete(val run: SpikeRun) : SpikeUiState
}
