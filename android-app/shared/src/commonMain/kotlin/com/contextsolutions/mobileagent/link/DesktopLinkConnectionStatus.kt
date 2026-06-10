package com.contextsolutions.mobileagent.link

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How a connected phone is reaching this desktop (#55). */
enum class MobileLinkKind { NONE, LAN, RELAY }

/**
 * Whether a paired mobile is *currently connected* to this desktop, and over which
 * transport (PR #57 / #55). The desktop Settings page reads it to show "Phone
 * connected via LAN/gateway" and enable Disconnect. On the desktop it's driven by
 * the LAN server's SSE subscriber count + the relay pipe state; on mobile it's
 * always NONE (the phone is the client, not the host). Mirrors [DesktopLinkQrProvider].
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
 * Desktop binding — a phone is connected over EITHER the LAN link server ([set],
 * driven by the SSE subscriber count) OR the relay ([setRelay], driven by the relay
 * host's pipe state). Separate sources → OR for [mobileConnected]; the relay (the
 * "anywhere" path) takes precedence for [connectionKind] if both are up.
 */
class MutableDesktopLinkConnectionStatus : DesktopLinkConnectionStatus {
    private val _connected = MutableStateFlow(false)
    override val mobileConnected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _kind = MutableStateFlow(MobileLinkKind.NONE)
    override val connectionKind: StateFlow<MobileLinkKind> = _kind.asStateFlow()

    private var lan = false
    private var relay = false

    /** LAN link-server subscriber present (PR #57). */
    @Synchronized
    fun set(connected: Boolean) {
        lan = connected
        recompute()
    }

    /** Relay pipe UP — a phone is connected over the E2EE relay (#55). */
    @Synchronized
    fun setRelay(connected: Boolean) {
        relay = connected
        recompute()
    }

    private fun recompute() {
        _connected.value = lan || relay
        _kind.value = when {
            relay -> MobileLinkKind.RELAY
            lan -> MobileLinkKind.LAN
            else -> MobileLinkKind.NONE
        }
    }
}
