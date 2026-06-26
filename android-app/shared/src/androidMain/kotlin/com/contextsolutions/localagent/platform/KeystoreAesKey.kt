package com.contextsolutions.localagent.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Provisions the single AES-256-GCM key that backs every at-rest secret on Android — the
 * Keystore-direct replacement for androidx's `MasterKey` (security finding L4). The key is
 * generated once in the hardware-backed `AndroidKeyStore` (TEE/StrongBox where available) and never
 * leaves it; the raw secret material is sealed/opened via [AesGcmEnvelope]. Shared by
 * [SecureStorageFactory] (the KV store) and
 * [com.contextsolutions.localagent.link.transport.AndroidKeystoreKeyStore] (the relay identity file)
 * so both ride one hardware key.
 */
internal object KeystoreAesKey {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val DEFAULT_ALIAS = "local_agent_secure_store_v1"

    /** The Keystore AES key under [alias], creating a 256-bit GCM key on first use. */
    fun loadOrCreate(alias: String = DEFAULT_ALIAS): SecretKey {
        val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keystore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
