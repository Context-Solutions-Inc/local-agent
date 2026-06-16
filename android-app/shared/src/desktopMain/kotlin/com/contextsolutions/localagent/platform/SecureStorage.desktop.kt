package com.contextsolutions.localagent.platform

import com.contextsolutions.localagent.inference.DesktopAppDirs
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Desktop [SecureStorage] backed by a password-protected **PKCS#12 KeyStore**
 * (Phase 6, docs/DESKTOP_PORT_PLAN.md). Each secret is stored as a
 * `SecretKeyEntry` (raw UTF-8 bytes) in `<app-data>/secrets.p12`, encrypted at
 * rest with the JDK's PBE — the Android side uses `EncryptedSharedPreferences`,
 * iOS uses the Keychain; this is the desktop counterpart.
 *
 * **Security tier.** This is the **fallback** the plan calls for. PKCS#12 keeps
 * the secrets non-plaintext on disk (PRD §4.4), but the store password is
 * derived from a stable per-user value (or the `LOCALAGENT_KEYSTORE_PASSWORD`
 * override) rather than an OS-protected secret — so it defends against casual
 * disk inspection, not an attacker who has both the file and the user identity.
 * The hardening upgrade is the **OS keyring** (java-keyring → Secret Service /
 * macOS Keychain / Windows Credential Manager), deferred so this increment stays
 * dependency-free and headless-safe (no desktop session needed in CI).
 *
 * No new dependency — pure JDK `java.security` / `javax.crypto`. Thread-safe via
 * coarse synchronization (secret access is rare: key read on search, write on
 * settings save).
 */
class DesktopSecureStorage internal constructor(
    private val storeFile: File,
    private val password: CharArray,
) : SecureStorage {

    private val keyStore: KeyStore = KeyStore.getInstance("PKCS12").apply {
        if (storeFile.isFile) {
            storeFile.inputStream().use { load(it, password) }
        } else {
            load(null, password) // initialise an empty store
        }
    }

    private val protection = KeyStore.PasswordProtection(password)

    // Arbitrary string secrets are stored as PBE keys — the portable way to put
    // a variable-length value into a KeyStore (a plain SecretKeySpec("raw") is
    // rejected by PKCS#12: "unrecognized algorithm name"). PBE passwords must be
    // ASCII, so the value is Base64(UTF-8) first — making any length / Unicode /
    // binary value round-trip. The PBE factory recovers the chars via
    // getKeySpec(PBEKeySpec).
    private val pbeFactory = SecretKeyFactory.getInstance("PBE")

    @Synchronized
    override fun put(key: String, value: String) {
        val encoded = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        val secret = pbeFactory.generateSecret(PBEKeySpec(encoded.toCharArray()))
        keyStore.setEntry(key, KeyStore.SecretKeyEntry(secret), protection)
        persist()
    }

    @Synchronized
    override fun get(key: String): String? {
        if (!keyStore.containsAlias(key)) return null
        val entry = keyStore.getEntry(key, protection) as? KeyStore.SecretKeyEntry ?: return null
        val spec = pbeFactory.getKeySpec(entry.secretKey, PBEKeySpec::class.java) as PBEKeySpec
        return String(Base64.getDecoder().decode(String(spec.password)), Charsets.UTF_8)
    }

    @Synchronized
    override fun remove(key: String) {
        if (keyStore.containsAlias(key)) {
            keyStore.deleteEntry(key)
            persist()
        }
    }

    @Synchronized
    override fun contains(key: String): Boolean = keyStore.containsAlias(key)

    private fun persist() {
        storeFile.parentFile?.mkdirs()
        storeFile.outputStream().use { keyStore.store(it, password) }
    }

    companion object {
        const val STORE_FILENAME: String = "secrets.p12"
        const val PASSWORD_ENV: String = "LOCALAGENT_KEYSTORE_PASSWORD"

        fun create(baseDir: File = DesktopAppDirs.dataDir()): DesktopSecureStorage =
            DesktopSecureStorage(File(baseDir, STORE_FILENAME), resolvePassword())

        private fun resolvePassword(): CharArray {
            System.getenv(PASSWORD_ENV)?.takeIf { it.isNotBlank() }?.let { return it.toCharArray() }
            // Stable per-user fallback — see the class KDoc on the security tier.
            val seed = buildString {
                append(System.getProperty("user.name").orEmpty())
                append('|')
                append(System.getProperty("user.home").orEmpty())
            }.ifBlank { "local-agent" }
            return seed.toCharArray()
        }
    }
}
