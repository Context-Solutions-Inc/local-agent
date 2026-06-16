package com.contextsolutions.localagent.inference

import com.contextsolutions.localagent.link.transport.LinkConnectionState
import com.contextsolutions.localagent.preferences.DesktopLinkPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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
 * [DesktopLinkStatusProvider] that reflects the live Secure Gateway **relay** pipe
 * state for the chat-header dot (relay-only since PR #80). Reports
 * [DesktopLinkStatus.DISABLED] when the link isn't configured (toggle off / no
 * relay QR scanned), else mirrors [relayState] (UP → connected, anything else →
 * unreachable). Used on both platforms — on desktop the link is never enabled, so
 * it simply reports [DesktopLinkStatus.DISABLED].
 */
class PollingDesktopLinkStatusProvider(
    private val preferences: DesktopLinkPreferences,
    // Relay pipe state (mobile, when subscribed). Null on desktop, where the link
    // is never enabled and the dot stays DISABLED.
    private val relayState: StateFlow<LinkConnectionState>? = null,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : DesktopLinkStatusProvider {

    private val _status = MutableStateFlow(DesktopLinkStatus.DISABLED)
    override val status: StateFlow<DesktopLinkStatus> = _status.asStateFlow()

    init {
        scope.launch {
            preferences.configFlow().collectLatest { cfg ->
                val rs = relayState
                if (!cfg.isLinkConfigured || rs == null) {
                    _status.value = DesktopLinkStatus.DISABLED
                    return@collectLatest
                }
                // Reflect the live relay pipe state (the relay has no pollable URL).
                rs.collect { st ->
                    _status.value =
                        if (st == LinkConnectionState.UP) DesktopLinkStatus.UP else DesktopLinkStatus.DOWN
                }
            }
        }
    }
}
