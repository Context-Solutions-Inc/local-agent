package com.contextsolutions.mobileagent.app.observability

import com.contextsolutions.mobileagent.inference.MemoryStatus
import com.contextsolutions.mobileagent.inference.SystemMemoryStatusProvider
import kotlinx.coroutines.flow.StateFlow

/**
 * Android [SystemMemoryStatusProvider] actual — re-exposes the process-singleton
 * [SystemMemoryMonitor]'s status flow (started from `MobileAgentApplication`).
 * Replaces the former `SystemMemoryStatusViewModel`, which only existed to reach
 * the monitor through `koinViewModel()`; the shared chat header now resolves this
 * provider via `koinInject` instead.
 */
class AndroidSystemMemoryStatusProvider(
    monitor: SystemMemoryMonitor,
) : SystemMemoryStatusProvider {
    override val status: StateFlow<MemoryStatus> = monitor.status
}
