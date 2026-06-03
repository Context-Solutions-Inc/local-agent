package com.contextsolutions.mobileagent.link

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether a paired mobile is *currently connected* to this desktop's link server
 * (PR #57). The desktop Settings page reads it to show "phone connected" and
 * enable Disconnect. On the desktop it's driven by the server's live SSE
 * subscriber count; on mobile it's always false (the phone is the client, not the
 * host). Mirrors [DesktopLinkQrProvider].
 */
interface DesktopLinkConnectionStatus {
    val mobileConnected: StateFlow<Boolean>
}

/** Mobile / non-host binding — always false. */
class NoDesktopLinkConnection : DesktopLinkConnectionStatus {
    override val mobileConnected: StateFlow<Boolean> = MutableStateFlow(false)
}

/** Desktop binding — the link server updates it as phones connect/disconnect. */
class MutableDesktopLinkConnectionStatus : DesktopLinkConnectionStatus {
    private val _connected = MutableStateFlow(false)
    override val mobileConnected: StateFlow<Boolean> = _connected.asStateFlow()
    fun set(connected: Boolean) {
        _connected.value = connected
    }
}
