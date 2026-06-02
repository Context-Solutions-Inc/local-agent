package com.contextsolutions.mobileagent.inference

import kotlinx.coroutines.flow.StateFlow

/**
 * Cross-platform source of the chat header's system-memory status dot
 * (docs/DESKTOP_PORT_PLAN.md Phase 9). Android wraps the process-singleton
 * `SystemMemoryMonitor` (whose bands mirror the watchdog thresholds in
 * [SystemMemoryThresholds]); desktop reports a constant healthy state (no
 * portable per-process RAM-pressure signal that maps to the Pixel-7 LMKD bands).
 *
 * Replaces the former `:androidApp` `SystemMemoryStatusViewModel`, so the
 * indicator lives in shared `:ui` and resolves the status through Koin.
 */
interface SystemMemoryStatusProvider {
    val status: StateFlow<MemoryStatus>
}
