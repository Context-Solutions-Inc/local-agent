package com.contextsolutions.localagent.link.transport

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.contextsolutions.securegateway.core.Crypto
import com.contextsolutions.securegateway.core.KeyPair
import com.contextsolutions.securegateway.core.keystore.KeyStore
import java.io.File

/**
 * Android [KeyStore] for the relay identity (the device's persistent X25519 keypair, #55).
 *
 * The Android hardware Keystore cannot hold a raw X25519/Curve25519 private key (it supports
 * EC NIST curves, RSA, AES, HMAC — not Curve25519), so the 32-byte X25519 private key is
 * generated in-app via libsodium ([Crypto.generateKeyPair]) and persisted in an androidx
 * [EncryptedFile] whose master key is a hardware-backed AndroidKeyStore AES-GCM key. The key
 * material is therefore encrypted at rest under a key that never leaves the secure hardware —
 * strictly better than the desktop [com.contextsolutions.securegateway.core.keystore.FileKeyStore]'s plaintext
 * hex file, which the relay transport used on-device before the real AAR (#55).
 *
 * The public key is re-derived on load. Replaces the prior `FileKeyStore(filesDir/relay_identity.key)`
 * wiring in [AndroidRelayBytePipeFactory].
 */
class AndroidKeystoreKeyStore(
    private val context: Context,
    fileName: String = "relay_identity.x25519.enc",
) : KeyStore {

    private val file = File(context.filesDir, fileName)
    private var identity: KeyPair? = null

    @Synchronized
    override fun loadOrCreateIdentity(): KeyPair {
        identity?.let { return it }
        val kp = if (file.exists()) {
            val priv = readEncrypted()
            KeyPair(priv, Crypto.publicFromPrivate(priv))
        } else {
            Crypto.generateKeyPair().also { writeEncrypted(it.privateKey()) }
        }
        identity = kp
        return kp
    }

    private fun encryptedFile(): EncryptedFile {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedFile.Builder(
            context,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()
    }

    private fun writeEncrypted(privateKey: ByteArray) {
        // EncryptedFile.openFileOutput() refuses to overwrite — clear any stale file first.
        if (file.exists()) file.delete()
        encryptedFile().openFileOutput().use { it.write(privateKey) }
    }

    private fun readEncrypted(): ByteArray =
        encryptedFile().openFileInput().use { it.readBytes() }
}
