package com.contextsolutions.mobileagent.app.ui.observability

import androidx.lifecycle.ViewModel
import com.contextsolutions.mobileagent.app.observability.SystemMemoryMonitor
import com.contextsolutions.mobileagent.inference.MemoryStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin Hilt-aware view over [SystemMemoryMonitor] so the chat-header
 * status dot can access the monitor through the standard
 * `hiltViewModel()` Compose extension. The monitor itself is a process
 * `@Singleton` started from `MobileAgentApplication.onCreate`; this
 * ViewModel just re-exposes its [status] flow without owning lifecycle.
 */
@HiltViewModel
class SystemMemoryStatusViewModel @Inject constructor(
    monitor: SystemMemoryMonitor,
) : ViewModel() {
    val status: StateFlow<MemoryStatus> = monitor.status
}
