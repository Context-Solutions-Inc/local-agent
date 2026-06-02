package com.contextsolutions.mobileagent.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves the PKCS#12-backed [DesktopSecureStorage] round-trips secrets on this
 * JDK (PKCS#12 `SecretKeyEntry` support has historically been version-sensitive)
 * and persists across re-opens. Runs in CI — no model/network needed.
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
}
