package com.contextsolutions.localagent.relay

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.contextsolutions.securegateway.core.Crypto
import com.contextsolutions.securegateway.core.Role
import com.contextsolutions.securegateway.core.Session
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke for the real Secure Gateway Android AAR (lazysodium-android, native arm64
 * libsodium) — CLAUDE.md #55, decision 4. The hermetic JVM `:java:e2eTest` proves the shared
 * crypto algorithm on lazysodium-JAVA; this proves the SAME E2EE stack actually loads and
 * round-trips under lazysodium-ANDROID on a Pixel 7 (where lazysodium-java's JNA build can't
 * load). Exercises X25519 derivation, ECDH, HKDF, and XChaCha20-Poly1305 seal/open via the
 * AndroidSodiumProvider ServiceLoader binding.
 */
@RunWith(AndroidJUnit4::class)
class RelayCryptoSmokeTest {

    @Test
    fun x25519_derivation_matches() {
        val kp = Crypto.generateKeyPair()
        // cryptoScalarMultBase on native arm64 libsodium must re-derive the same public key.
        assertArrayEquals(kp.publicKey(), Crypto.publicFromPrivate(kp.privateKey()))
    }

    @Test
    fun session_seals_and_opens_round_trip() {
        val mobile = Crypto.generateKeyPair()
        val desktop = Crypto.generateKeyPair()
        // Both sides must agree on the same handshake nonces (exchanged during pairing).
        val mNonce = Crypto.newHandshakeNonce()
        val dNonce = Crypto.newHandshakeNonce()

        val mobileSession = Session.create(mobile.privateKey(), desktop.publicKey(), Role.MOBILE, mNonce, dNonce)
        val desktopSession = Session.create(desktop.privateKey(), mobile.publicKey(), Role.DESKTOP, mNonce, dNonce)

        val plaintext = "relay crypto on-device ✓".toByteArray(Charsets.UTF_8)
        val id = "smoke"
        val ts = 1_780_000_000L

        // Mobile seals (XChaCha20-Poly1305), desktop opens — proves the full directional
        // key derivation + AEAD agree across the two sessions on native libsodium.
        val wire = mobileSession.seal(id, ts, plaintext)
        assertArrayEquals(plaintext, desktopSession.open(id, ts, wire))

        // And the reverse direction.
        val reply = "ack".toByteArray(Charsets.UTF_8)
        assertArrayEquals(reply, mobileSession.open(id, ts + 1, desktopSession.seal(id, ts + 1, reply)))

        // Tamper detection: a flipped ciphertext byte must fail to open.
        val tampered = wire.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        val opened = runCatching { desktopSession.open(id, ts, tampered) }
        assertEquals(true, opened.isFailure)
    }
}
