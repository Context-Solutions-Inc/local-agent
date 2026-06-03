package com.contextsolutions.mobileagent.inference

import com.contextsolutions.mobileagent.preferences.DesktopLinkPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** State of the mobile↔desktop link for the chat header dot (PR #57). */
enum class DesktopLinkStatus {
    /** Link toggle off or no desktop paired — render no dot. */
    DISABLED,

    /** Paired + enabled + the desktop link server answers — green. */
    UP,

    /** Paired + enabled but the desktop is unreachable — red. */
    DOWN,
}

/**
 * Cross-platform source of the chat header's desktop-link status dot (PR #57),
 * sibling to [SystemMemoryStatusProvider]. The `:ui` chat screen resolves it via
 * Koin and renders green ([DesktopLinkStatus.UP]) / red ([DesktopLinkStatus.DOWN])
 * next to the memory dot, or nothing when [DesktopLinkStatus.DISABLED].
 */
interface DesktopLinkStatusProvider {
    val status: StateFlow<DesktopLinkStatus>
}

/**
 * [DesktopLinkStatusProvider] that polls the paired desktop's `/health` so the dot
 * reflects reachability live (independent of whether a chat turn has run yet).
 * Re-probes immediately when the link config changes (toggle / re-pair) and stops
 * polling when the link isn't configured. Used on both platforms — on desktop the
 * link is never enabled, so it simply reports [DesktopLinkStatus.DISABLED].
 */
class PollingDesktopLinkStatusProvider(
    private val preferences: DesktopLinkPreferences,
    private val client: DesktopLinkClient,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) : DesktopLinkStatusProvider {

    private val _status = MutableStateFlow(DesktopLinkStatus.DISABLED)
    override val status: StateFlow<DesktopLinkStatus> = _status.asStateFlow()

    init {
        scope.launch {
            preferences.configFlow().collectLatest { cfg ->
                if (!cfg.isLinkConfigured) {
                    _status.value = DesktopLinkStatus.DISABLED
                    return@collectLatest
                }
                val baseUrl = cfg.baseUrl() ?: run {
                    _status.value = DesktopLinkStatus.DISABLED
                    return@collectLatest
                }
                while (isActive) {
                    val ok = client.health(baseUrl, cfg.pairingToken)
                    _status.value = if (ok) DesktopLinkStatus.UP else DesktopLinkStatus.DOWN
                    delay(pollIntervalMs)
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 10_000L
    }
}
