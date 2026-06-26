package com.contextsolutions.localagent.link.transport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * commonMain mirror of the Secure Gateway SDK's relay QR JSON (#23 — commonMain
 * must not reference `com.contextsolutions.securegateway.*`). Used only to **detect + classify** a
 * scanned QR (relay vs the LAN `magent://` URI) and pull out the bits the app
 * persists; the SDK re-parses the raw JSON itself when pairing.
 */
@Serializable
data class RelayQrPayload(
    val v: Int = 0,
    @SerialName("pairing_token") val pairingToken: String = "",
    @SerialName("desktop_pubkey") val desktopPubkey: String = "",
    @SerialName("desktop_device_id") val desktopDeviceId: String = "",
    val endpoints: Map<String, String> = emptyMap(),
    /**
     * Legacy (pre-L2): the desktop used to embed its account secret here so the phone could issue
     * tokens. Security L2 removed that — new desktops no longer emit it, and the phone authenticates
     * with the per-pair credential minted at pairing. Kept (defaulting to "") only so an old QR still
     * parses; not persisted by the consumer anymore.
     */
    @SerialName("account_secret") val accountSecret: String = "",
) {
    /** A well-formed relay QR (v1+, with a pairing token and a relay endpoint). */
    val isRelayQr: Boolean
        get() = v >= 1 && pairingToken.isNotBlank() && endpoints["relay"]?.isNotBlank() == true

    companion object {
        // coerceInputValues: the SDK's QrPayload serializes with a plain Jackson ObjectMapper (no
        // NON_NULL include), so since L2 dropped the account-secret injection the QR now carries
        // `"account_secret":null`. Without coercion kotlinx-serialization throws on a present-null
        // for a non-nullable field (defaults apply only to ABSENT keys), so parseOrNull returned
        // null and the phone reported the QR "UNRECOGNIZED" — pairing silently failed. Coercion maps
        // a present-null to the field default; isRelayQr still validates the load-bearing fields.
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        /** Parse a scanned string as a relay QR, or null if it isn't one. */
        fun parseOrNull(raw: String): RelayQrPayload? =
            runCatching { json.decodeFromString(serializer(), raw.trim()) }
                .getOrNull()
                ?.takeIf { it.isRelayQr }
    }
}
