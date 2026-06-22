package com.contextsolutions.localagent.link.transport

import android.content.Context
import com.contextsolutions.localagent.observability.ContentRedactor
import com.contextsolutions.localagent.platform.SecureStorage
import com.contextsolutions.localagent.platform.SecureStorageKeys
import com.contextsolutions.securegateway.core.auth.QrPayload
import com.contextsolutions.securegateway.mobile.MobileConfig
import com.contextsolutions.securegateway.mobile.SecureGateway
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Builds a connected [AndroidRelayBytePipe] from a scanned relay QR. Parses the QR
 * for the auth/relay endpoints + account secret, pairs the mobile SDK, wires the
 * byte-pipe callbacks, then connects. The X25519 identity persists across re-launch via
 * [AndroidKeystoreKeyStore] — an androidx EncryptedFile under a hardware-backed Android
 * Keystore master key (CLAUDE.md #55; AndroidKeyStore can't hold raw X25519 directly).
 *
 * **Pair once, reconnect many.** The QR's pairing token is single-use, so a reconnect
 * (Desktop Agent Connection toggled off→on, or an app relaunch — both rebuild the pipe via
 * [create]) must NOT replay it, or the gateway returns `401 pairing_token_invalid`. The
 * first successful pair persists `{pairingToken, deviceId, pairId, desktopPublicKey}` in
 * [SecureStorage]; a later [create] whose QR carries that same token restores the pairing
 * and calls `connect()` directly. A freshly scanned QR (new token, e.g. after the desktop
 * re-mints on revoke) no longer matches, so it pairs fresh and overwrites the saved state.
 */
class AndroidRelayBytePipeFactory(
    private val context: Context,
    private val secureStorage: SecureStorage,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: (String) -> Unit = {},
) : RelayBytePipeFactory {

    @Serializable
    private data class SavedPairing(
        val pairingToken: String,
        val deviceId: String,
        val pairId: String,
        val desktopPublicKey: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun create(relayQrJson: String): RelayBytePipe? = withContext(ioDispatcher) {
        runCatching {
            val qr = QrPayload.fromJson(relayQrJson)
            logger("create: relay QR auth=${qr.authEndpoint()} relay=${qr.relayEndpoint()} secret=${if (qr.accountSecret.isNullOrBlank()) "MISSING" else "present"}")

            // Reconnect with the saved pairing only when it matches THIS QR's (single-use) token.
            val saved = loadPairing()?.takeIf { it.pairingToken == qr.pairingToken }

            val config = MobileConfig().apply {
                authUrl = qr.authEndpoint() ?: error("relay QR missing auth endpoint")
                relayUrl = qr.relayEndpoint()
                accountSecret = qr.accountSecret
                keyStore = AndroidKeystoreKeyStore(context)
                // Security M3: scrub the SDK's diagnostic output (it may carry
                // wss URLs with token query strings / auth headers) before it
                // reaches any sink — defense-in-depth even on internal builds.
                logger = { this@AndroidRelayBytePipeFactory.logger("[sdk] ${ContentRedactor.redact(it).orEmpty()}") }
                if (saved != null) {
                    deviceId = saved.deviceId
                    pairId = saved.pairId
                    desktopPublicKeyB64 = saved.desktopPublicKey
                }
            }
            val client = SecureGateway.mobile(config)

            if (client.isPaired()) {
                logger("create: reconnecting with saved pairing (pairId=${saved?.pairId}) — skipping pair()")
            } else {
                logger("create: pairing…")
                client.pair(qr)
                // Persist so the next toggle/relaunch reconnects instead of replaying the token.
                runCatching {
                    val deviceId = client.deviceId()
                    val pairId = client.pairId()
                    val desktopKey = client.desktopPublicKeyB64()
                    if (deviceId != null && pairId != null && desktopKey != null) {
                        secureStorage.put(
                            SecureStorageKeys.RELAY_PAIRING_STATE,
                            json.encodeToString(
                                SavedPairing.serializer(),
                                SavedPairing(qr.pairingToken, deviceId, pairId, desktopKey),
                            ),
                        )
                        logger("create: pairing persisted (deviceId=$deviceId pairId=$pairId)")
                    }
                }.onFailure { logger("create: failed to persist pairing: ${it.message}") }
            }

            val pipe = AndroidRelayBytePipe(client, logger) // wires callbacks before connect()
            logger("create: connecting (wss dial is async — connect() returns before the socket opens)…")
            client.connect()
            logger("create: connect kicked off; watch 'pipe: state' + '[sdk] wss:' lines for the actual outcome")
            pipe
        }.getOrElse {
            // Pairing (the HTTPS device-register / complete-pairing / token-issue calls) is the
            // synchronous part that lands here; the async wss dial surfaces via [sdk] wss: lines.
            logger("create: pair/connect FAILED ${it.javaClass.simpleName}: ${it.message}")
            it.cause?.let { c -> logger("create:   cause ${c.javaClass.simpleName}: ${c.message}") }
            it.stackTrace.take(6).forEach { f -> logger("create:   at $f") }
            null
        }
    }

    private fun loadPairing(): SavedPairing? =
        secureStorage.get(SecureStorageKeys.RELAY_PAIRING_STATE)
            ?.let { runCatching { json.decodeFromString(SavedPairing.serializer(), it) }.getOrNull() }
}
