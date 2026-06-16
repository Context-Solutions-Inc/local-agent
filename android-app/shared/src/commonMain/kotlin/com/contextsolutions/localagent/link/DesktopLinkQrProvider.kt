package com.contextsolutions.localagent.link

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Supplies the desktop pairing-QR payload string to the shared Settings UI (PR #57;
 * relay-only since PR #80). On desktop it carries the Secure Gateway **relay** QR
 * JSON, published only while a subscription is active (the UI renders it as a QR
 * image, desktop-only). On mobile it is always null — the phone scans a QR, it
 * doesn't show one.
 */
interface DesktopLinkQrProvider {
    val qrPayload: StateFlow<String?>

    /**
     * Epoch-ms at which the currently-shown pairing QR expires, or null when no QR is
     * shown. Drives the desktop "Pairing code expires in Ns" countdown (PR #92); read
     * the published deadline so the countdown can't drift from the host's actual timeout.
     */
    val qrExpiresAtEpochMs: StateFlow<Long?>
}

/** Mobile / no-QR binding — always null. */
class NoDesktopLinkQr : DesktopLinkQrProvider {
    override val qrPayload: StateFlow<String?> = MutableStateFlow(null)
    override val qrExpiresAtEpochMs: StateFlow<Long?> = MutableStateFlow(null)
}

/** Desktop binding — [Main] sets the payload once the link server is bound. */
class MutableDesktopLinkQr : DesktopLinkQrProvider {
    private val _payload = MutableStateFlow<String?>(null)
    override val qrPayload: StateFlow<String?> = _payload.asStateFlow()
    private val _expiresAt = MutableStateFlow<Long?>(null)
    override val qrExpiresAtEpochMs: StateFlow<Long?> = _expiresAt.asStateFlow()
    fun set(payload: String?, expiresAtEpochMs: Long? = null) {
        _payload.value = payload
        _expiresAt.value = expiresAtEpochMs
    }
}
