package com.contextsolutions.mobileagent.app.ui.download

import androidx.lifecycle.ViewModel
import com.contextsolutions.mobileagent.app.service.DownloadState
import com.contextsolutions.mobileagent.app.service.ModelDownloadController
import com.contextsolutions.mobileagent.app.service.ModelInventory
import com.contextsolutions.mobileagent.app.service.ModelSpec
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

    fun spec(): ModelSpec = inventory.spec()

    fun start(allowMetered: Boolean) = controller.start(allowMetered)
    fun pause() = controller.pause()
    fun resume(allowMetered: Boolean) = controller.resume(allowMetered)
    fun retry(allowMetered: Boolean) = controller.retry(allowMetered)
}
