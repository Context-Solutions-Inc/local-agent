package com.contextsolutions.mobileagent.subscription

import com.contextsolutions.mobileagent.platform.SecureStorage
import com.contextsolutions.mobileagent.platform.SecureStorageKeys
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PR #90 — the persisted "peer paired" marker keeps "Mobile agent offline" + Disconnect
 * visible while a paired phone is away, including across a desktop restart, and clears on
 * a desktop-initiated Disconnect. (The UP/REVOKED transitions run inside the relay
 * collector over a live `DesktopClient` — exercised by manual end-to-end testing.)
 */
class DesktopRelayHostTest {

    @Test
    fun peerPairedSeededFalseWhenNoMarker() {
        val host = newHost(FakeSecureStorage())
        assertFalse(host.peerPaired.value)
    }

    @Test
    fun peerPairedSeededTrueFromPersistedMarker() {
        // Simulates a desktop restart while a previously-paired phone is offline.
        val store = FakeSecureStorage().apply { put(SecureStorageKeys.RELAY_PEER_PAIRED, "1") }
        val host = newHost(store)
        assertTrue(host.peerPaired.value)
    }

    @Test
    fun disconnectClearsTheMarker() = runBlocking {
        val store = FakeSecureStorage().apply { put(SecureStorageKeys.RELAY_PEER_PAIRED, "1") }
        val host = newHost(store)
        assertTrue(host.peerPaired.value)

        host.disconnect()

        assertFalse(host.peerPaired.value)
        assertFalse(store.contains(SecureStorageKeys.RELAY_PEER_PAIRED))
    }

    @Test
    fun disconnectClearsTheSavedPairing() = runBlocking {
        // PR #91 — a desktop Disconnect revokes the pair at the gateway, so the persisted
        // pairing (reconnect-without-repair) must be dropped too, leaving the next launch to
        // mint a fresh QR. The device id survives so that fresh pairing reuses the slot.
        val store = FakeSecureStorage().apply {
            put(SecureStorageKeys.RELAY_PEER_PAIRED, "1")
            put(SecureStorageKeys.RELAY_DESKTOP_PAIR_ID, "pair_abc")
            put(SecureStorageKeys.RELAY_MOBILE_PUBLIC_KEY, "bW9iaWxlS2V5")
            put(SecureStorageKeys.RELAY_DESKTOP_DEVICE_ID, "dev_xyz")
        }
        val host = newHost(store)

        host.disconnect()

        assertFalse(store.contains(SecureStorageKeys.RELAY_DESKTOP_PAIR_ID))
        assertFalse(store.contains(SecureStorageKeys.RELAY_MOBILE_PUBLIC_KEY))
        assertFalse(store.contains(SecureStorageKeys.RELAY_PEER_PAIRED))
        // The device id is intentionally kept — a fresh pairing reuses the max_pairs slot.
        assertTrue(store.contains(SecureStorageKeys.RELAY_DESKTOP_DEVICE_ID))
    }

    private fun newHost(store: SecureStorage) = DesktopRelayHost(
        prefs = NoOpSubscriptionPreferences(),
        secureStorage = store,
        gatewayBaseUrl = "http://localhost",
        relayWsUrl = "ws://localhost",
        keyStorePath = Files.createTempFile("relay_identity", ".key"),
    )

    private class FakeSecureStorage : SecureStorage {
        private val map = HashMap<String, String>()
        override fun put(key: String, value: String) { map[key] = value }
        override fun get(key: String): String? = map[key]
        override fun remove(key: String) { map.remove(key) }
        override fun contains(key: String): Boolean = map.containsKey(key)
    }
}
