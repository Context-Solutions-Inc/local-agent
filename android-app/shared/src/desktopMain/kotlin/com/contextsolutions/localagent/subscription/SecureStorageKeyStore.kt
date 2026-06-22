package com.contextsolutions.localagent.subscription

import com.contextsolutions.localagent.platform.SecureStorage
import com.contextsolutions.localagent.platform.SecureStorageKeys
import com.contextsolutions.securegateway.core.Crypto
import com.contextsolutions.securegateway.core.Hex
import com.contextsolutions.securegateway.core.KeyPair
import com.contextsolutions.securegateway.core.keystore.KeyStore
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

        // 1. Already in the encrypted store. Security L6: the identity is migrated, so any
        // surviving legacy plaintext file is safe to remove now — retry the deletion every
        // launch (a delete that failed during the one-time migration in step 2 would otherwise
        // leave the cleartext private key on disk forever, since step 2 never runs again).
        secureStorage.get(SecureStorageKeys.RELAY_IDENTITY_KEY)?.takeIf { it.isNotBlank() }?.let { hex ->
            purgeLegacyFileIfPresent()
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

    /**
     * Best-effort deletion of the legacy plaintext [legacyFile] once the identity already
     * lives in [secureStorage]. Safe to call repeatedly; a failure just logs and is retried
     * on the next launch. NEVER call this before the key has been imported into the store
     * (it would discard a not-yet-migrated identity and force a re-pair).
     */
    private fun purgeLegacyFileIfPresent() {
        val file = legacyFile ?: return
        runCatching {
            if (Files.exists(file)) {
                Files.delete(file)
                logger("keystore: removed leftover legacy relay identity file $file")
            }
        }.onFailure {
            // Security L6: the identity already lives in the encrypted store, so a surviving
            // plaintext private key on disk is a real exposure — escalate the message (not a
            // quiet info line) and register a JVM-exit deletion as a belt-and-suspenders. The
            // every-launch retry in loadOrCreateIdentity() remains the primary remediation.
            logger(
                "keystore: SECURITY WARNING — could not delete leftover plaintext relay " +
                    "identity file $file (${it.message}); the X25519 private key remains on " +
                    "disk. Will retry next launch; attempting JVM-exit cleanup.",
            )
            runCatching { file.toFile().deleteOnExit() }
        }
    }

    private fun fromHex(hex: String): KeyPair {
        val priv = Hex.decode(hex)
        return KeyPair(priv, Crypto.publicFromPrivate(priv))
    }
}
