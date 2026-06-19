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
 * **Security tier (H2).** The store password is no longer derived from a
 * reconstructable per-user value — at rest the secrets are protected by a
 * password that cannot be recovered from the file plus the user identity.
 * [KeystorePassword] resolves it with precedence:
 *  1. `LOCALAGENT_KEYSTORE_PASSWORD` env override — always wins (deployment / headless / recovery).
 *  2. OS keyring via CLI shell-out (Linux `secret-tool`/libsecret, macOS `security`/Keychain) —
 *     read-or-generate a [java.security.SecureRandom] password; skipped when `LOCALAGENT_HEADLESS=1` or on Windows.
 *  3. A `0600` password file in the app-data dir (headless / no keyring / Windows / CI) — read-or-generate.
 *
 * **File modes.** `secrets.p12` and the password file are `0600`, the app-data
 * dir `0700` — best-effort on POSIX ([PosixPerms]); a silent no-op on Windows
 * (ACL-based), where protection then relies on the per-user ACL of `%LOCALAPPDATA%`.
 *
 * **Migration.** A store created under the old derived password (or a prior env
 * value) is transparently re-keyed on first launch under the resolved password —
 * no data loss (see the migration loop in the constructor).
 *
 * **Known limitation.** A store created under a keyring password and later opened
 * headless with neither the env override nor the password file present is
 * unrecoverable — set `LOCALAGENT_KEYSTORE_PASSWORD` for headless/server deployments.
 *
 * No new dependency — pure JDK `java.security` / `javax.crypto` + the OS keyring
 * CLI. Thread-safe via coarse synchronization (secret access is rare: key read on
 * search, write on settings save).
 */
class DesktopSecureStorage internal constructor(
    private val storeFile: File,
    private val password: CharArray,
    candidates: List<CharArray>,
) : SecureStorage {

    /**
     * Backward-compatible constructor (used by tests): opens the store under a
     * single [password], with no migration/re-key.
     */
    internal constructor(storeFile: File, password: CharArray) :
        this(storeFile, password, listOf(password))

    private val keyStore: KeyStore
    private val protection: KeyStore.PasswordProtection

    init {
        if (storeFile.isFile) {
            // Try each candidate (primary first, legacy derived last) so an existing
            // store created under an older password still opens. A fresh KeyStore per
            // attempt — a failed load() leaves the instance in an undefined state.
            var opened: KeyStore? = null
            var openedWith: CharArray? = null
            for (cand in candidates) {
                val ks = KeyStore.getInstance("PKCS12")
                val ok = runCatching { storeFile.inputStream().use { ks.load(it, cand) } }.isSuccess
                if (ok) {
                    opened = ks
                    openedWith = cand
                    break
                }
            }
            keyStore = opened ?: throw IllegalStateException(
                "secrets.p12 is present but no candidate password opened it. The store may have " +
                    "been created under an OS-keyring password that is now unavailable; set " +
                    "${KeystorePassword.PASSWORD_ENV} to recover (see DesktopSecureStorage KDoc).",
            )
            protection = KeyStore.PasswordProtection(password)
            // Opened under a non-primary candidate (legacy derivation, old env, …) →
            // re-key under the resolved password. Each PKCS#12 SecretKeyEntry is
            // individually password-protected, so re-storing the file alone is NOT
            // enough — re-set every entry under the new protection, then persist.
            if (!openedWith!!.contentEquals(password)) {
                val oldProtection = KeyStore.PasswordProtection(openedWith)
                for (alias in keyStore.aliases().toList()) {
                    val entry = keyStore.getEntry(alias, oldProtection)
                    keyStore.setEntry(alias, entry, protection)
                }
                persist()
            }
        } else {
            keyStore = KeyStore.getInstance("PKCS12").apply { load(null, password) }
            protection = KeyStore.PasswordProtection(password)
        }
    }

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
        // Re-assert owner-only on every write (best-effort; no-op on Windows/ACL).
        PosixPerms.restrict(storeFile, PosixPerms.FILE_0600)
    }

    companion object {
        const val STORE_FILENAME: String = "secrets.p12"

        fun create(baseDir: File = DesktopAppDirs.dataDir()): DesktopSecureStorage {
            baseDir.mkdirs()
            // Lock the app-data dir to 0700 before any secret lands in it.
            PosixPerms.restrict(baseDir, PosixPerms.DIR_0700)
            val resolved = KeystorePassword.resolve(
                baseDir,
                logger = { System.err.println("[SecureStorage] $it") },
            )
            return DesktopSecureStorage(File(baseDir, STORE_FILENAME), resolved.primary, resolved.candidates)
        }
    }
}
