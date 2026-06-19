package com.contextsolutions.localagent.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves the PKCS#12-backed [DesktopSecureStorage] round-trips secrets on this
 * JDK (PKCS#12 `SecretKeyEntry` support has historically been version-sensitive)
 * and persists across re-opens, plus the H2 hardening: a non-derivable resolved
 * password, transparent re-key migration, and 0600/0700 file modes. Runs in CI —
 * no model/network/OS-keyring needed (the keyring is faked via [KeyringBackend]).
 */
class DesktopSecureStorageTest {

    private val tmpDir: File = Files.createTempDirectory("secure-storage-test").toFile()
    private val password = "test-password".toCharArray()

    @AfterTest
    fun cleanup() {
        tmpDir.deleteRecursively()
    }

    private fun newStore(): DesktopSecureStorage =
        DesktopSecureStorage(File(tmpDir, "secrets.p12"), password.copyOf())

    // --- existing round-trip coverage (also proves the (File, CharArray) ctor still works) ---

    @Test
    fun put_get_contains_remove_round_trip() {
        val store = newStore()
        assertNull(store.get("missing"), "absent key should read null")
        assertFalse(store.contains("missing"))

        store.put("brave_api_key", "BSA-secret-123")
        assertTrue(store.contains("brave_api_key"))
        assertEquals("BSA-secret-123", store.get("brave_api_key"))

        store.put("brave_api_key", "BSA-rotated-456")
        assertEquals("BSA-rotated-456", store.get("brave_api_key"), "overwrite should win")

        store.remove("brave_api_key")
        assertFalse(store.contains("brave_api_key"))
        assertNull(store.get("brave_api_key"))
    }

    @Test
    fun persists_across_reopen() {
        newStore().put("hf_auth_token", "hf_xyz")
        // A fresh instance over the same file + password must see the secret.
        assertEquals("hf_xyz", newStore().get("hf_auth_token"))
    }

    @Test
    fun handles_unicode_and_long_values() {
        val store = newStore()
        val value = "café—naïve 🔑 " + "x".repeat(2000)
        store.put("k", value)
        assertEquals(value, newStore().get("k"))
    }

    // --- H2: password resolution ---

    @Test
    fun resolved_password_is_not_derivable() {
        val res = KeystorePassword.resolve(tmpDir, env = { null }, keyring = NoKeyringBackend)
        val legacy = String(KeystorePassword.legacyDerivedPassword())
        assertFalse(
            String(res.primary) == legacy,
            "resolved password must not be the derivable user.name|user.home value",
        )
        assertTrue(res.primary.size >= 32, "resolved password should be high-entropy (>=32 chars)")
        assertEquals(KeystorePassword.Source.FILE, res.source)
    }

    @Test
    fun file_fallback_persists_password() {
        val first = KeystorePassword.resolve(tmpDir, env = { null }, keyring = NoKeyringBackend)
        val second = KeystorePassword.resolve(tmpDir, env = { null }, keyring = NoKeyringBackend)
        assertEquals(String(first.primary), String(second.primary), "file fallback must reuse the stored password")
        assertTrue(File(tmpDir, KeystorePassword.PASSWORD_FILENAME).isFile, "password file should exist")
    }

    @Test
    fun keyring_generates_and_reuses() {
        val keyring = InMemoryKeyringBackend()
        val first = KeystorePassword.resolve(tmpDir, env = { null }, keyring = keyring)
        assertEquals(KeystorePassword.Source.KEYRING, first.source)
        val second = KeystorePassword.resolve(tmpDir, env = { null }, keyring = keyring)
        assertEquals(String(first.primary), String(second.primary), "keyring must read back the same password")
        assertFalse(
            File(tmpDir, KeystorePassword.PASSWORD_FILENAME).isFile,
            "keyring path should not write a password file",
        )
    }

    @Test
    fun env_override_wins_over_keyring_and_file() {
        val keyring = InMemoryKeyringBackend()
        // Pre-populate keyring + file so we can prove env still wins.
        KeystorePassword.resolve(tmpDir, env = { null }, keyring = keyring)
        val res = KeystorePassword.resolve(
            tmpDir,
            env = { name -> if (name == KeystorePassword.PASSWORD_ENV) "env-pw" else null },
            keyring = keyring,
        )
        assertEquals("env-pw", String(res.primary))
        assertEquals(KeystorePassword.Source.ENV, res.source)
    }

    // --- H2: migration / re-key ---

    @Test
    fun migrates_legacy_store_to_random_password() {
        val storeFile = File(tmpDir, "secrets.p12")
        val legacy = KeystorePassword.legacyDerivedPassword()
        // Create a store under the OLD derived password.
        DesktopSecureStorage(storeFile, legacy.copyOf()).put("relay_identity_key", "deadbeef")

        val fresh = "fresh-random-password-0123456789".toCharArray()
        // Open via the migration path: candidates = [primary, legacy].
        val migrated = DesktopSecureStorage(storeFile, fresh.copyOf(), listOf(fresh.copyOf(), legacy.copyOf()))
        assertEquals("deadbeef", migrated.get("relay_identity_key"), "secret must survive the re-key")

        // The new password now opens it...
        assertEquals("deadbeef", DesktopSecureStorage(storeFile, fresh.copyOf()).get("relay_identity_key"))
        // ...and the legacy password no longer does (re-keyed).
        assertFailsWith<Throwable>("legacy password must no longer open the re-keyed store") {
            DesktopSecureStorage(storeFile, legacy.copyOf()).get("relay_identity_key")
        }
    }

    @Test
    fun migrates_legacy_store_to_env_password() {
        val storeFile = File(tmpDir, "secrets.p12")
        val legacy = KeystorePassword.legacyDerivedPassword()
        DesktopSecureStorage(storeFile, legacy.copyOf()).put("brave_api_key", "BSA-1")

        val env = "env-pw".toCharArray()
        val migrated = DesktopSecureStorage(storeFile, env.copyOf(), listOf(env.copyOf(), legacy.copyOf()))
        assertEquals("BSA-1", migrated.get("brave_api_key"))
        assertEquals("BSA-1", DesktopSecureStorage(storeFile, env.copyOf()).get("brave_api_key"))
        assertFailsWith<Throwable> {
            DesktopSecureStorage(storeFile, legacy.copyOf()).get("brave_api_key")
        }
    }

    @Test
    fun unrecoverable_when_no_candidate_opens() {
        val storeFile = File(tmpDir, "secrets.p12")
        // Store created under password "X" — neither candidate below will open it.
        DesktopSecureStorage(storeFile, "X".toCharArray()).put("k", "v")
        val ex = assertFailsWith<IllegalStateException> {
            DesktopSecureStorage(
                storeFile,
                "fresh".toCharArray(),
                listOf("fresh".toCharArray(), KeystorePassword.legacyDerivedPassword()),
            )
        }
        assertTrue(
            ex.message?.contains(KeystorePassword.PASSWORD_ENV) == true,
            "error should point at the env-override recovery path",
        )
    }

    // --- H2: file modes (POSIX only) ---

    @Test
    fun posix_modes_locked() {
        val path = tmpDir.toPath()
        if (!PosixPerms.isPosix(path)) return // Windows / non-POSIX FS — perms are a no-op by design.

        // create() in a sub-dir so we can assert the dir mode (tmpDir itself is harness-created).
        val dataDir = File(tmpDir, "data")
        val store = DesktopSecureStorage.create(dataDir)
        store.put("k", "v") // force a secrets.p12 write

        assertEquals(
            "rwx------",
            PosixFilePermissions.toString(Files.getPosixFilePermissions(dataDir.toPath())),
            "app-data dir must be 0700",
        )
        assertEquals(
            "rw-------",
            PosixFilePermissions.toString(Files.getPosixFilePermissions(File(dataDir, "secrets.p12").toPath())),
            "secrets.p12 must be 0600",
        )
        val passFile = File(dataDir, KeystorePassword.PASSWORD_FILENAME)
        if (passFile.isFile) {
            assertEquals(
                "rw-------",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(passFile.toPath())),
                "password file must be 0600",
            )
        }
    }
}
