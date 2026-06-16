package com.contextsolutions.localagent.subscription

import com.contextsolutions.localagent.platform.SecureStorage
import com.contextsolutions.localagent.platform.SecureStorageKeys
import com.securegateway.core.Crypto
import com.securegateway.core.Hex
import com.securegateway.core.KeyPair
import com.securegateway.core.keystore.KeyStore
import java.nio.file.Files
import java.nio.file.Path

/**
 * Desktop relay [KeyStore] that keeps the X25519 identity private key in the app's
 * encrypted [SecureStorage] (`secrets.p12`) instead of the SDK's default loose
 * plaintext `relay_identity.key` file (PR #80). This unifies the relay credential
 * material — account secret ([SecureStorageKeys.RELAY_ACCOUNT_SECRET]), device id
 * ([SecureStorageKeys.RELAY_DESKTOP_DEVICE_ID]) and now the identity key — under one
 * store/tier, and removes a private key sitting in cleartext on disk.
 *
 * The private key is stored hex-encoded under [SecureStorageKeys.RELAY_IDENTITY_KEY];
 * the public key is re-derived on load (matching the SDK's `FileKeyStore`).
 *
 * **Migration:** on first read, if the store has no key but a legacy
 * [legacyFile] (`relay_identity.key`) exists, its hex key is imported into the store
 * and the plaintext file is deleted — so existing installs keep their relay identity
 * (and their already-paired phone) without re-pairing.
 *
 * Security tier note: [SecureStorage] on desktop is a password-protected PKCS#12
 * store whose password defaults to a per-user derivation (or the
 * `LOCALAGENT_KEYSTORE_PASSWORD` override) — so this defends against casual disk
 * inspection and matches the account-secret tier, not an OS-keyring-grade secret.
 */
class SecureStorageKeyStore(
    private val secureStorage: SecureStorage,
    private val legacyFile: Path? = null,
    private val logger: (String) -> Unit = {},
) : KeyStore {

    private var identity: KeyPair? = null

    @Synchronized
    override fun loadOrCreateIdentity(): KeyPair {
        identity?.let { return it }

        // 1. Already in the encrypted store.
        secureStorage.get(SecureStorageKeys.RELAY_IDENTITY_KEY)?.takeIf { it.isNotBlank() }?.let { hex ->
            return fromHex(hex).also { identity = it }
        }

        // 2. One-time migration from the legacy plaintext file, if present.
        legacyFile?.let { file ->
            runCatching {
                if (Files.exists(file)) {
                    val hex = Files.readString(file).trim()
                    if (hex.isNotBlank()) {
                        val kp = fromHex(hex)
                        secureStorage.put(SecureStorageKeys.RELAY_IDENTITY_KEY, hex)
                        runCatching { Files.delete(file) }
                            .onFailure { logger("keystore: migrated identity but could not delete $file: ${it.message}") }
                        logger("keystore: migrated relay identity from $file into secure storage")
                        identity = kp
                        return kp
                    }
                }
            }.onFailure { logger("keystore: legacy identity migration failed: ${it.message}") }
        }

        // 3. Generate a fresh identity and persist it.
        val kp = Crypto.generateKeyPair()
        secureStorage.put(SecureStorageKeys.RELAY_IDENTITY_KEY, Hex.encode(kp.privateKey()))
        logger("keystore: generated a new relay identity")
        identity = kp
        return kp
    }

    private fun fromHex(hex: String): KeyPair {
        val priv = Hex.decode(hex)
        return KeyPair(priv, Crypto.publicFromPrivate(priv))
    }
}
