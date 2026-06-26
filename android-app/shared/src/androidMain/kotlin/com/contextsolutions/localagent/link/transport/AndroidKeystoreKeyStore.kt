package com.contextsolutions.localagent.link.transport

import android.content.Context
import com.contextsolutions.localagent.platform.AesGcmEnvelope
import com.contextsolutions.localagent.platform.KeystoreAesKey
import com.contextsolutions.securegateway.core.Crypto
import com.contextsolutions.securegateway.core.KeyPair
import com.contextsolutions.securegateway.core.keystore.KeyStore
import java.io.File

/**
 * Android [KeyStore] for the relay identity (the device's persistent X25519 keypair, #55).
 *
 * The Android hardware Keystore cannot hold a raw X25519/Curve25519 private key (it supports
 * EC NIST curves, RSA, AES, HMAC — not Curve25519), so the 32-byte X25519 private key is
 * generated in-app via libsodium ([Crypto.generateKeyPair]) and persisted **sealed under a
 * hardware-backed AndroidKeyStore AES-256-GCM key** ([KeystoreAesKey] + [AesGcmEnvelope]). The key
 * material is therefore encrypted at rest under a key that never leaves the secure hardware —
 * strictly better than the desktop [com.contextsolutions.securegateway.core.keystore.FileKeyStore]'s
 * plaintext hex file.
 *
 * This is the Keystore-direct replacement for the prior androidx `EncryptedFile` (security finding
 * L4 — that library is a deprecated alpha). It shares the one AES key with [SecureStorage]. The
 * public key is re-derived on load. The legacy `relay_identity.x25519.enc` (androidx) is cleared by
 * [com.contextsolutions.localagent.platform.CleanBreakReset] on a pre-L4 upgrade — there is no
 * read-old migration, so the phone re-pairs (accepted clean-break behavior).
 */
class AndroidKeystoreKeyStore(
    context: Context,
    fileName: String = "relay_identity.x25519.gcm",
) : KeyStore {

    private val file = File(context.filesDir, fileName)
    private val key by lazy { KeystoreAesKey.loadOrCreate() }
    private var identity: KeyPair? = null

    @Synchronized
    override fun loadOrCreateIdentity(): KeyPair {
        identity?.let { return it }
        val kp = if (file.exists()) {
            val priv = AesGcmEnvelope.open(key, file.readBytes())
            KeyPair(priv, Crypto.publicFromPrivate(priv))
        } else {
            Crypto.generateKeyPair().also { file.writeBytes(AesGcmEnvelope.seal(key, it.privateKey())) }
        }
        identity = kp
        return kp
    }
}
