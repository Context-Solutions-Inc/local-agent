package com.contextsolutions.mobileagent.link

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How a connected phone is reaching this desktop (#55; relay-only since PR #80). */
enum class MobileLinkKind { NONE, RELAY }

/**
 * Whether a paired mobile is *currently connected* to this desktop, over the Secure
 * Gateway relay (PR #57 / #55; LAN removed in PR #80). The desktop Settings page
 * reads it to show "Phone connected via gateway" and enable Disconnect. On the
 * desktop it's driven by the relay pipe state; on mobile it's always NONE (the
 * phone is the client, not the host). Mirrors [DesktopLinkQrProvider].
 */
interface DesktopLinkConnectionStatus {
    val mobileConnected: StateFlow<Boolean>

    /** Which transport the connected phone is using (NONE when not connected). */
    val connectionKind: StateFlow<MobileLinkKind> get() = NONE_KIND
}

private val NONE_KIND: StateFlow<MobileLinkKind> = MutableStateFlow(MobileLinkKind.NONE)

/** Mobile / non-host binding — always disconnected. */
class NoDesktopLinkConnection : DesktopLinkConnectionStatus {
    override val mobileConnected: StateFlow<Boolean> = MutableStateFlow(false)
}

/**
 * Desktop binding — a phone is connected over the Secure Gateway relay ([setRelay],
 * driven by the relay host's pipe state). (LAN removed in PR #80.)
 */
class MutableDesktopLinkConnectionStatus : DesktopLinkConnectionStatus {
    private val _connected = MutableStateFlow(false)
    override val mobileConnected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _kind = MutableStateFlow(MobileLinkKind.NONE)
    override val connectionKind: StateFlow<MobileLinkKind> = _kind.asStateFlow()

    /** Relay pipe UP — a phone is connected over the E2EE relay (#55). */
    @Synchronized
    fun setRelay(connected: Boolean) {
        _connected.value = connected
        _kind.value = if (connected) MobileLinkKind.RELAY else MobileLinkKind.NONE
    }
}
