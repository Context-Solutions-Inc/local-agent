package com.contextsolutions.mobileagent.link

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Supplies the desktop pairing-QR payload string ([LinkPairingPayload.encode]) to
 * the shared Settings UI (PR #57). On desktop it carries the running link
 * server's `magent://link?…` URI (the UI renders it as a QR image, desktop-only).
 * On mobile it is always null — the phone scans a QR, it doesn't show one.
 */
interface DesktopLinkQrProvider {
    val qrPayload: StateFlow<String?>
}

/** Mobile / no-QR binding — always null. */
class NoDesktopLinkQr : DesktopLinkQrProvider {
    override val qrPayload: StateFlow<String?> = MutableStateFlow(null)
}

/** Desktop binding — [Main] sets the payload once the link server is bound. */
class MutableDesktopLinkQr : DesktopLinkQrProvider {
    private val _payload = MutableStateFlow<String?>(null)
    override val qrPayload: StateFlow<String?> = _payload.asStateFlow()
    fun set(payload: String?) {
        _payload.value = payload
    }
}
