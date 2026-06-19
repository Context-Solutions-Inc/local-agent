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

    @Test
    fun `ModelSpec requiresHfAuth defaults to false`() {
        assertFalse(ModelSpec("f", "https://x/y", "abc", 1L).requiresHfAuth)
    }

    @Test
    fun `Gemma spec requires HF auth`() {
        // The HF-hosted Gemma artifact carries the bearer token; the CDN aux
        // models must not (PR #3).
        assertTrue(ModelInventory.SPEC.requiresHfAuth)
    }

    @Test
    fun `aux specs are CDN-hosted, configured, and never carry HF auth`() {
        for (spec in AndroidAuxModels.SPECS) {
            assertTrue(spec.isConfigured)
            assertFalse(spec.requiresHfAuth)
            assertEquals("${AndroidAuxModels.BASE_URL}/${spec.filename}", spec.downloadUrl)
            assertEquals(64, spec.sha256.length)
            assertTrue(spec.sizeBytes > 0L)
        }
    }

    @Test
    fun `aux spec coordinates are pinned to the shipped artifacts`() {
        assertEquals(67_688_256L, AndroidAuxModels.CLASSIFIER_SPEC.sizeBytes)
        assertEquals(
            "5920733f96bfc2f193fdebc7ef5585cd37ecc3b9f23b21259e448410679ea83d",
            AndroidAuxModels.CLASSIFIER_SPEC.sha256,
        )
        assertEquals(23_536_088L, AndroidAuxModels.EMBEDDER_SPEC.sizeBytes)
        assertEquals(
            "d4320c6f082450d542949ca1067cbc82de4c0c4c4f2ff8915752ff0885c55dcb",
            AndroidAuxModels.EMBEDDER_SPEC.sha256,
        )
    }
}
