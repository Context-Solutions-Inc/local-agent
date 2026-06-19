package com.contextsolutions.localagent.app.ui.download

import androidx.lifecycle.ViewModel
import com.contextsolutions.localagent.app.service.DownloadState
import com.contextsolutions.localagent.app.service.ModelDownloadController
import com.contextsolutions.localagent.app.service.ModelInventory
import com.contextsolutions.localagent.app.service.ModelSpec
import kotlinx.coroutines.flow.StateFlow

/**
 * Pass-through over [ModelDownloadController]. The controller is the singleton;
 * this VM exists only to bind it into Compose's lifecycle.
 */
class DownloadViewModel(
    private val controller: ModelDownloadController,
    private val inventory: ModelInventory,
) : ViewModel() {

    val state: StateFlow<DownloadState> = controller.state

    /** The primary (Gemma LLM) spec — used for the spec-configured warning. */
    fun spec(): ModelSpec = inventory.spec()

    /** Every model the one-time download fetches: Gemma LLM + aux models (PR #3). */
    fun specs(): List<ModelSpec> = inventory.requiredSpecs()

    /** Total bytes the one-time download fetches: Gemma LLM + aux models (PR #3). */
    fun totalDownloadBytes(): Long = inventory.requiredSpecs().sumOf { it.sizeBytes }

    fun start(allowMetered: Boolean) = controller.start(allowMetered)
    fun pause() = controller.pause()
    fun resume(allowMetered: Boolean) = controller.resume(allowMetered)
    fun retry(allowMetered: Boolean) = controller.retry(allowMetered)
}
