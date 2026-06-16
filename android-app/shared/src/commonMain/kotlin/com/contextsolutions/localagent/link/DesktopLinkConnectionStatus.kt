package com.contextsolutions.localagent.link

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How a connected phone is reaching this desktop (#55; relay-only since PR #80). */
enum class MobileLinkKind { NONE, RELAY }

/**
 * The desktop's view of a mobile peer (PR #90). Distinguishes a phone that has *never*
 * paired ([UNPAIRED]) from one that is paired but temporarily away ([OFFLINE]) — the
 * relay's `DOWN` vs `DISABLED` distinction the status used to collapse, which made an
 * offline phone read as "No phone paired yet". [OFFLINE] keeps the Disconnect button
 * visible (the phone is still paired); [UNPAIRED] hides it.
 */
enum class MobileLinkPresence { UNPAIRED, OFFLINE, CONNECTED }

/**
 * Whether a paired mobile is *currently connected* to this desktop, over the Secure
 * Gateway relay (PR #57 / #55; LAN removed in PR #80). The desktop Settings page
 * reads it to show "Local Agent connected via gateway" and enable Disconnect. On the
 * desktop it's driven by the relay pipe state; on mobile it's always NONE/UNPAIRED (the
 * phone is the client, not the host). Mirrors [DesktopLinkQrProvider].
 */
interface DesktopLinkConnectionStatus {
    val mobileConnected: StateFlow<Boolean>

    /** Which transport the connected phone is using (NONE when not connected). */
    val connectionKind: StateFlow<MobileLinkKind> get() = NONE_KIND

    /** Three-state presence: never paired / paired-but-offline / connected (PR #90). */
    val presence: StateFlow<MobileLinkPresence> get() = UNPAIRED_PRESENCE
}

private val NONE_KIND: StateFlow<MobileLinkKind> = MutableStateFlow(MobileLinkKind.NONE)
private val UNPAIRED_PRESENCE: StateFlow<MobileLinkPresence> =
    MutableStateFlow(MobileLinkPresence.UNPAIRED)

/** Mobile / non-host binding — always disconnected. */
class NoDesktopLinkConnection : DesktopLinkConnectionStatus {
    override val mobileConnected: StateFlow<Boolean> = MutableStateFlow(false)
}

/**
 * Desktop binding — a phone is connected over the Secure Gateway relay ([update],
 * driven by the relay host's pipe state + persisted paired marker). (LAN removed in PR #80.)
 */
class MutableDesktopLinkConnectionStatus : DesktopLinkConnectionStatus {
    private val _connected = MutableStateFlow(false)
    override val mobileConnected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _kind = MutableStateFlow(MobileLinkKind.NONE)
    override val connectionKind: StateFlow<MobileLinkKind> = _kind.asStateFlow()

    private val _presence = MutableStateFlow(MobileLinkPresence.UNPAIRED)
    override val presence: StateFlow<MobileLinkPresence> = _presence.asStateFlow()

    /**
     * Reflect the live relay state: [connected] = relay pipe UP, [everPaired] = a phone
     * has paired (persisted marker, survives the phone going offline + a desktop restart).
     * CONNECTED ⇒ relay up; OFFLINE ⇒ paired but down; UNPAIRED ⇒ never paired (PR #90).
     */
    @Synchronized
    fun update(connected: Boolean, everPaired: Boolean) {
        _presence.value = when {
            connected -> MobileLinkPresence.CONNECTED
            everPaired -> MobileLinkPresence.OFFLINE
            else -> MobileLinkPresence.UNPAIRED
        }
        _connected.value = connected
        _kind.value = if (connected) MobileLinkKind.RELAY else MobileLinkKind.NONE
    }
}
