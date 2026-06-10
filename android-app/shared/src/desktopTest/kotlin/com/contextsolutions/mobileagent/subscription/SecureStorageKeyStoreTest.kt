package com.contextsolutions.mobileagent.subscription

import com.contextsolutions.mobileagent.platform.SecureStorage
import com.contextsolutions.mobileagent.platform.SecureStorageKeys
import com.securegateway.core.Crypto
import com.securegateway.core.Hex
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** PR #80 — relay identity key lives in SecureStorage (secrets.p12), not a plaintext file. */
class SecureStorageKeyStoreTest {

    @Test
    fun generatesAndPersistsThenReturnsSameIdentity() {
        val store = FakeSecureStorage()
        val ks = SecureStorageKeyStore(store)

        val first = ks.loadOrCreateIdentity()
        // Persisted as hex under the relay identity key.
        val hex = store.get(SecureStorageKeys.RELAY_IDENTITY_KEY)
        assertNotNull(hex)
        assertContentEquals(first.privateKey(), Hex.decode(hex))

        // A fresh keystore over the same store loads the SAME identity (no regen).
        val reloaded = SecureStorageKeyStore(store).loadOrCreateIdentity()
        assertContentEquals(first.privateKey(), reloaded.privateKey())
        assertContentEquals(first.publicKey(), reloaded.publicKey())
    }

    @Test
    fun migratesLegacyPlaintextFileThenDeletesIt() {
        val store = FakeSecureStorage()
        val legacy = Files.createTempFile("relay_identity", ".key")
        val kp = Crypto.generateKeyPair()
        Files.writeString(legacy, Hex.encode(kp.privateKey()))

        val loaded = SecureStorageKeyStore(store, legacy).loadOrCreateIdentity()

        // Imported the legacy key, removed the plaintext file.
        assertContentEquals(kp.privateKey(), loaded.privateKey())
        assertEquals(Hex.encode(kp.privateKey()), store.get(SecureStorageKeys.RELAY_IDENTITY_KEY))
        assertFalse(Files.exists(legacy), "legacy plaintext file should be deleted after migration")
    }

    @Test
    fun storeValueWinsOverLegacyFile() {
        val store = FakeSecureStorage()
        val stored = Crypto.generateKeyPair()
        store.put(SecureStorageKeys.RELAY_IDENTITY_KEY, Hex.encode(stored.privateKey()))

        val legacy = Files.createTempFile("relay_identity", ".key")
        Files.writeString(legacy, Hex.encode(Crypto.generateKeyPair().privateKey())) // a DIFFERENT key

        val loaded = SecureStorageKeyStore(store, legacy).loadOrCreateIdentity()

        assertContentEquals(stored.privateKey(), loaded.privateKey())
        assertTrue(Files.exists(legacy), "legacy file is untouched when the store already has a key")
        Files.deleteIfExists(legacy)
    }

    private class FakeSecureStorage : SecureStorage {
        private val map = HashMap<String, String>()
        override fun put(key: String, value: String) { map[key] = value }
        override fun get(key: String): String? = map[key]
        override fun remove(key: String) { map.remove(key) }
        override fun contains(key: String): Boolean = map.containsKey(key)
    }
}
