package com.contextsolutions.localagent.subscription

import com.contextsolutions.localagent.platform.SecureStorage
import com.contextsolutions.localagent.platform.SecureStorageKeys
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
    fun storeValueWinsOverLegacyFileAndPurgesIt() {
        val store = FakeSecureStorage()
        val stored = Crypto.generateKeyPair()
        store.put(SecureStorageKeys.RELAY_IDENTITY_KEY, Hex.encode(stored.privateKey()))

        val legacy = Files.createTempFile("relay_identity", ".key")
        Files.writeString(legacy, Hex.encode(Crypto.generateKeyPair().privateKey())) // a DIFFERENT key

        val loaded = SecureStorageKeyStore(store, legacy).loadOrCreateIdentity()

        // The store's identity still wins (we never re-import a leftover file)...
        assertContentEquals(stored.privateKey(), loaded.privateKey())
        // ...and security L6: the leftover plaintext file is purged once the store has the key,
        // so a deletion that failed during the original migration is retried on a later load.
        assertFalse(
            Files.exists(legacy),
            "leftover legacy plaintext file should be purged when the store already has a key",
        )
    }

    @Test
    fun retriesLegacyDeletionOnEveryLoad() {
        // Simulate a migration whose plaintext-file delete failed: the key is in the store but
        // the legacy file still exists. A subsequent launch (fresh keystore over the same store)
        // must clean it up rather than leave the cleartext key on disk forever.
        val store = FakeSecureStorage()
        val kp = Crypto.generateKeyPair()
        store.put(SecureStorageKeys.RELAY_IDENTITY_KEY, Hex.encode(kp.privateKey()))
        val legacy = Files.createTempFile("relay_identity", ".key")
        Files.writeString(legacy, Hex.encode(kp.privateKey()))

        SecureStorageKeyStore(store, legacy).loadOrCreateIdentity()

        assertFalse(Files.exists(legacy), "a leftover legacy file is retried + deleted on the next load")
    }

    @Test
    fun escalatesWhenLeftoverLegacyFileCannotBeDeleted() {
        // Security L6: the store already holds the identity but the plaintext file can't be
        // deleted (here a NON-EMPTY directory at the legacy path — Files.delete throws). The
        // migration must still succeed AND surface a clearly-marked SECURITY warning rather
        // than a quiet info line, so a lingering cleartext private key is visible.
        val store = FakeSecureStorage()
        val kp = Crypto.generateKeyPair()
        store.put(SecureStorageKeys.RELAY_IDENTITY_KEY, Hex.encode(kp.privateKey()))

        val legacyDir = Files.createTempDirectory("relay_identity_dir")
        Files.createTempFile(legacyDir, "child", ".tmp") // makes the dir undeletable

        val logs = mutableListOf<String>()
        val loaded = SecureStorageKeyStore(store, legacyDir, logger = { logs.add(it) }).loadOrCreateIdentity()

        // Identity still resolves from the store...
        assertContentEquals(kp.privateKey(), loaded.privateKey())
        // ...and the failure was escalated, not whispered.
        assertTrue(
            logs.any { it.contains("SECURITY WARNING") },
            "a persistent legacy-file deletion failure must log a SECURITY WARNING; got $logs",
        )
    }

    private class FakeSecureStorage : SecureStorage {
        private val map = HashMap<String, String>()
        override fun put(key: String, value: String) { map[key] = value }
        override fun get(key: String): String? = map[key]
        override fun remove(key: String) { map.remove(key) }
        override fun contains(key: String): Boolean = map.containsKey(key)
    }
}
