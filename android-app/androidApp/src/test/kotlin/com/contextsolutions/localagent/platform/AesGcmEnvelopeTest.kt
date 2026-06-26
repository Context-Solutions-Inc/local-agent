package com.contextsolutions.localagent.platform

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Exercises the L4 at-rest primitive ([AesGcmEnvelope]) with a software AES-256 key — the same
 * envelope the production AndroidKeyStore key uses, so the round-trip / tamper / IV-uniqueness
 * guarantees are verified without a device.
 */
class AesGcmEnvelopeTest {

    private fun key(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `seal then open round-trips`() {
        val k = key()
        val plaintext = "brave-api-key-éñ 🔑".encodeToByteArray()
        val recovered = AesGcmEnvelope.open(k, AesGcmEnvelope.seal(k, plaintext))
        assertArrayEquals(plaintext, recovered)
    }

    @Test
    fun `empty plaintext round-trips`() {
        val k = key()
        assertEquals(0, AesGcmEnvelope.open(k, AesGcmEnvelope.seal(k, ByteArray(0))).size)
    }

    @Test
    fun `fresh IV per seal — same plaintext yields different ciphertext`() {
        val k = key()
        val plaintext = "same".encodeToByteArray()
        val a = AesGcmEnvelope.seal(k, plaintext)
        val b = AesGcmEnvelope.seal(k, plaintext)
        // First 12 bytes are the IV; it must differ (and so must the whole blob).
        assertFalse(a.copyOfRange(0, 12).contentEquals(b.copyOfRange(0, 12)))
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val k = key()
        val sealed = AesGcmEnvelope.seal(k, "secret".encodeToByteArray())
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0x01).toByte()
        assertThrows(AEADBadTagException::class.java) { AesGcmEnvelope.open(k, sealed) }
    }

    @Test
    fun `wrong key fails authentication`() {
        val sealed = AesGcmEnvelope.seal(key(), "secret".encodeToByteArray())
        assertThrows(AEADBadTagException::class.java) { AesGcmEnvelope.open(key(), sealed) }
    }

    @Test
    fun `too-short blob is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { AesGcmEnvelope.open(key(), ByteArray(8)) }
    }
}
