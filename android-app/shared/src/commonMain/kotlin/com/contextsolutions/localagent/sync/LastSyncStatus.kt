package com.contextsolutions.localagent.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reactive view of the last-successful-sync wall-clock time (PR #70), so the Jobs
 * screen can render "Synced 4m ago". Mirrors the mutable-holder-behind-interface
 * pattern of [com.contextsolutions.localagent.link.DesktopLinkConnectionStatus]:
 * bind [MutableLastSyncStatus] as the single, expose [LastSyncStatus] read-only.
 *
 * `null` = never synced.
 */
interface LastSyncStatus {
    val lastSyncedAtMs: StateFlow<Long?>
}

class MutableLastSyncStatus(initial: Long?) : LastSyncStatus {
    private val _flow = MutableStateFlow(initial)
    override val lastSyncedAtMs: StateFlow<Long?> = _flow.asStateFlow()

    fun mark(epochMs: Long) {
        _flow.value = epochMs
    }
}
