package com.contextsolutions.localagent.link.transport

import com.contextsolutions.localagent.platform.SecureStorage
import com.contextsolutions.localagent.platform.SecureStorageKeys
import com.contextsolutions.localagent.platform.platformIoDispatcher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Builds a connected [IosRelayBytePipe] from a scanned relay QR. iOS mirror of
 * `AndroidRelayBytePipeFactory`: parses the [RelayQrPayload] for the auth/relay endpoints, seeds
 * a [NativeRelayConfig], pairs (or reconnects) the Swift `MobileClient` via [NativeRelayBridge],
 * wires the byte-pipe callbacks, then connects. The X25519 identity persists across relaunch via
 * the Swift SDK's own `KeychainKeyStore` (so no Kotlin KeyStore is needed here).
 *
 * **Pair once, reconnect many.** The QR's pairing token is single-use, so a reconnect (Desktop
 * Agent Connection toggled off→on, or an app relaunch — both rebuild the pipe via [create]) must
 * NOT replay it. The first successful pair persists `{pairingToken, deviceId, pairId,
 * desktopPublicKey, pairCredential}` in [SecureStorage] (`RELAY_PAIRING_STATE`, the SAME blob shape
 * as Android); a later [create] whose QR carries that same token restores the pairing and connects
 * directly. A freshly scanned QR (new token) pairs fresh and overwrites the saved state.
 */
class IosRelayBytePipeFactory(
    private val bridge: NativeRelayBridge,
    private val secureStorage: SecureStorage,
    private val ioDispatcher: CoroutineDispatcher = platformIoDispatcher,
    private val logger: (String) -> Unit = {},
    // Invoked when a fresh pair lands on a DIFFERENT desktop than the one we were last paired to
    // (stable desktop X25519 pubkey changed). Jobs are desktop-specific, so the binding wipes local
    // jobs + resets the sync watermark. Default no-op keeps tests / non-DI callers compiling.
    private val onPairedDifferentDesktop: suspend () -> Unit = {},
) : RelayBytePipeFactory {

    // SAME schema + field names as AndroidRelayBytePipeFactory.SavedPairing (blob shape reused).
    @Serializable
    private data class SavedPairing(
        val pairingToken: String,
        val deviceId: String,
        val pairId: String,
        val desktopPublicKey: String,
        val pairCredential: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun create(relayQrJson: String): RelayBytePipe? = withContext(ioDispatcher) {
        runCatching {
            // commonMain QR mirror — NOT the SDK type (#23). Swift re-parses the raw JSON in pair().
            val qr = RelayQrPayload.parseOrNull(relayQrJson) ?: error("relay QR unparseable")
            logger("create: relay QR auth=${qr.endpoints["auth"]} relay=${qr.endpoints["relay"]}")

            // Load the prior pairing UNCONDITIONALLY: the new-desktop check compares the STABLE
            // desktop pubkey (the QR can't carry it reliably). Reconnect only when the saved token
            // matches THIS QR's (single-use) token.
            val priorPairing = loadPairing()
            val saved = priorPairing?.takeIf { it.pairingToken == qr.pairingToken }

            val config = NativeRelayConfig(
                authUrl = qr.endpoints["auth"] ?: error("relay QR missing auth endpoint"),
                relayUrl = qr.endpoints["relay"],
                accountSecret = qr.accountSecret, // legacy fallback only (L2 uses pairCredential)
                deviceId = saved?.deviceId,
                pairId = saved?.pairId,
                desktopPublicKeyB64 = saved?.desktopPublicKey,
                pairCredential = saved?.pairCredential,
            )
            val session = bridge.open(config) // ↔ SecureGateway.mobile(config)

            if (session.isPaired()) {
                logger("create: reconnecting with saved pairing (pairId=${saved?.pairId}) — skipping pair()")
            } else {
                logger("create: pairing…")
                session.pairAwait(relayQrJson)
                val desktopKey = session.desktopPublicKeyB64()
                // Jobs are desktop-specific: a fresh pair on a DIFFERENT desktop (stable pubkey
                // changed) wipes stale local jobs + resets the sync watermark BEFORE connect().
                if (priorPairing != null &&
                    priorPairing.desktopPublicKey.isNotBlank() &&
                    !desktopKey.isNullOrBlank() &&
                    desktopKey != priorPairing.desktopPublicKey
                ) {
                    logger("create: paired a DIFFERENT desktop (pubkey changed) — wiping stale local jobs")
                    runCatching { onPairedDifferentDesktop() }
                        .onFailure { logger("create: new-desktop wipe failed: ${it.message}") }
                }
                // Persist so the next toggle/relaunch reconnects instead of replaying the token.
                runCatching {
                    val deviceId = session.deviceId()
                    val pairId = session.currentPairId()
                    val cred = session.pairCredential()
                    if (deviceId != null && pairId != null && desktopKey != null) {
                        secureStorage.put(
                            SecureStorageKeys.RELAY_PAIRING_STATE,
                            json.encodeToString(
                                SavedPairing.serializer(),
                                SavedPairing(qr.pairingToken, deviceId, pairId, desktopKey, cred),
                            ),
                        )
                        logger("create: pairing persisted (deviceId=$deviceId pairId=$pairId cred=${if (cred.isNullOrBlank()) "none" else "present"})")
                    }
                }.onFailure { logger("create: failed to persist pairing: ${it.message}") }
            }

            val pipe = IosRelayBytePipe(session, logger) // wires callbacks before connect()
            logger("create: connecting (wss dial is async — connect() returns before the socket opens)…")
            pipe.connect()
            pipe
        }.getOrElse {
            logger("create: pair/connect FAILED ${it::class.simpleName}: ${it.message}")
            null
        }
    }

    private fun loadPairing(): SavedPairing? =
        secureStorage.get(SecureStorageKeys.RELAY_PAIRING_STATE)
            ?.let { runCatching { json.decodeFromString(SavedPairing.serializer(), it) }.getOrNull() }
}

private suspend fun NativeRelaySession.pairAwait(qrJson: String) =
    suspendCancellableCoroutine<Unit> { cont ->
        pair(
            qrJson,
            onDone = { if (cont.isActive) cont.resume(Unit) },
            onError = { msg -> if (cont.isActive) cont.resumeWithException(IllegalStateException(msg)) },
        )
    }
