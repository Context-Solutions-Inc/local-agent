package com.contextsolutions.mobileagent.link

/**
 * The data encoded in the desktop's pairing QR (PR #57): the desktop's LAN
 * endpoint, a bearer token, and its device id. The phone scans it, stores it in
 * `DesktopLinkPreferences`, then POSTs `/pair` so the desktop records the phone.
 *
 * Wire form is a compact URI: `magent://link?h=<host>&p=<port>&t=<token>&d=<deviceId>`.
 * Kept tiny so the QR stays low-density and scans reliably on a phone camera.
 */
data class LinkPairingPayload(
    val host: String,
    val port: Int,
    val token: String,
    val deviceId: String,
) {
    fun encode(): String =
        "$SCHEME://link?h=${host.encode()}&p=$port&t=${token.encode()}&d=${deviceId.encode()}"

    companion object {
        const val SCHEME = "magent"

        /** Parse a scanned QR string, or null if it isn't a valid pairing URI. */
        fun parse(raw: String): LinkPairingPayload? {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("$SCHEME://link?")) return null
            val query = trimmed.substringAfter('?', "")
            val params = query.split('&').mapNotNull { pair ->
                val eq = pair.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                pair.substring(0, eq) to pair.substring(eq + 1).decode()
            }.toMap()
            val host = params["h"]?.takeIf { it.isNotBlank() } ?: return null
            val port = params["p"]?.toIntOrNull() ?: return null
            val token = params["t"]?.takeIf { it.isNotBlank() } ?: return null
            val deviceId = params["d"].orEmpty()
            return LinkPairingPayload(host = host, port = port, token = token, deviceId = deviceId)
        }

        // Minimal percent-encoding for the few chars that can appear in a token
        // (UUIDs are URL-safe, but be defensive). Avoids a platform URL dep.
        private fun String.encode(): String = buildString {
            for (c in this@encode) when (c) {
                '&', '=', '?', '#', '%', ' ' -> append('%').append(c.code.toString(16).uppercase().padStart(2, '0'))
                else -> append(c)
            }
        }

        private fun String.decode(): String {
            if ('%' !in this) return this
            val sb = StringBuilder()
            var i = 0
            while (i < length) {
                val c = this[i]
                if (c == '%' && i + 2 < length) {
                    val code = substring(i + 1, i + 3).toIntOrNull(16)
                    if (code != null) {
                        sb.append(code.toChar()); i += 3; continue
                    }
                }
                sb.append(c); i++
            }
            return sb.toString()
        }
    }
}
