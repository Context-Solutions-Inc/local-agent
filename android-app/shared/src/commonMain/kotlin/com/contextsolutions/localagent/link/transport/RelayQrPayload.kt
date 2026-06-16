package com.contextsolutions.localagent.link.transport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * commonMain mirror of the Secure Gateway SDK's relay QR JSON (#23 — commonMain
 * must not reference `com.securegateway.*`). Used only to **detect + classify** a
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
    @SerialName("account_secret") val accountSecret: String = "",
) {
    /** A well-formed relay QR (v1+, with a pairing token and a relay endpoint). */
    val isRelayQr: Boolean
        get() = v >= 1 && pairingToken.isNotBlank() && endpoints["relay"]?.isNotBlank() == true

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parse a scanned string as a relay QR, or null if it isn't one. */
        fun parseOrNull(raw: String): RelayQrPayload? =
            runCatching { json.decodeFromString(serializer(), raw.trim()) }
                .getOrNull()
                ?.takeIf { it.isRelayQr }
    }
}
