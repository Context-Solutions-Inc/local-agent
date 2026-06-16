package com.contextsolutions.localagent.app.service

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct tests over [ModelInventory.Companion.sha256Of]. The instance methods
 * (isPresent, verifyChecksum) wrap an Android [android.content.Context] and would
 * need Robolectric to exercise; the streaming-hash routine is the only piece
 * with non-trivial logic and it lives on the companion.
 */
class ModelInventoryTest {

    @Test
    fun `sha256Of computes correct hash for empty file`() {
        val tmp = File.createTempFile("inventory-test-empty", null).apply { deleteOnExit() }
        // Empty SHA-256.
        val expected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertEquals(expected, ModelInventory.sha256Of(tmp))
    }

    @Test
    fun `sha256Of computes correct hash for known content`() {
        val tmp = File.createTempFile("inventory-test", null).apply { deleteOnExit() }
        tmp.writeBytes("hello world".toByteArray(Charsets.UTF_8))
        val expected = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
        assertEquals(expected, ModelInventory.sha256Of(tmp))
    }

    @Test
    fun `sha256Of streams large content correctly`() {
        // 5 MB of pseudo-random bytes — exercises the multi-chunk path (64 KB buffer).
        val bytes = ByteArray(5 * 1024 * 1024).also { buf ->
            var seed = 0
            for (i in buf.indices) {
                seed = seed * 1103515245 + 12345
                buf[i] = (seed shr 16).toByte()
            }
        }
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

        val tmp = File.createTempFile("inventory-test-large", null).apply { deleteOnExit() }
        tmp.writeBytes(bytes)
        assertEquals(expected, ModelInventory.sha256Of(tmp))
    }

    @Test
    fun `ModelSpec isConfigured requires url and sha and size`() {
        assertFalse(
            ModelSpec("f", "", "abc", 100L).isConfigured,
        )
        assertFalse(
            ModelSpec("f", "https://x/y", "", 100L).isConfigured,
        )
        assertFalse(
            ModelSpec("f", "https://x/y", "abc", 0L).isConfigured,
        )
        assertTrue(
            ModelSpec("f", "https://x/y", "abc", 1L).isConfigured,
        )
    }
}
