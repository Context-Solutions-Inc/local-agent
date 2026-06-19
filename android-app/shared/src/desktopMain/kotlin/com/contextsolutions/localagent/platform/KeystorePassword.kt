package com.contextsolutions.localagent.platform

import com.contextsolutions.localagent.notification.DesktopOs
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64

/**
 * Resolves the password that protects the desktop PKCS#12 secret store
 * (`secrets.p12`) — security fix H2. The old derivation `"${user.name}|${user.home}"`
 * was reconstructable from the file path / machine usernames, so the store was
 * effectively plaintext at rest. This replaces it with a non-derivable password.
 *
 * Precedence:
 *  1. `LOCALAGENT_KEYSTORE_PASSWORD` env override — always wins (deployment / headless / recovery).
 *  2. OS keyring via CLI shell-out (Linux `secret-tool`/libsecret, macOS `security`/Keychain):
 *     read existing, else generate a [SecureRandom] password and store it. Skipped when
 *     `LOCALAGENT_HEADLESS=1` or on Windows.
 *  3. 0600 password file in the app-data dir: read existing, else generate + write.
 *
 * The keyring is behind the injectable [KeyringBackend] seam so unit tests never
 * touch a real OS keyring.
 */
internal object KeystorePassword {
    const val KEYRING_SERVICE: String = "LocalAgent"
    const val KEYRING_ACCOUNT: String = "secrets.p12"
    const val PASSWORD_FILENAME: String = "secrets.p12.pass"
    const val PASSWORD_ENV: String = "LOCALAGENT_KEYSTORE_PASSWORD"
    const val HEADLESS_ENV: String = "LOCALAGENT_HEADLESS"

    enum class Source { ENV, KEYRING, FILE }

    /**
     * [primary] is the password to (re)key the store under; [candidates] is the
     * ordered list to try when opening an existing store (primary first, then the
     * legacy derived password for transparent migration).
     */
    data class Result(
        val primary: CharArray,
        val candidates: List<CharArray>,
        val source: Source,
    )

    fun resolve(
        baseDir: File,
        env: (String) -> String? = System::getenv,
        keyring: KeyringBackend = defaultBackend(env),
        random: () -> CharArray = ::generateRandomPassword,
        logger: (String) -> Unit = {},
    ): Result {
        val legacy = legacyDerivedPassword()

        // 1. Env override always wins.
        env(PASSWORD_ENV)?.takeIf { it.isNotBlank() }?.let { e ->
            val pw = e.toCharArray()
            return Result(pw, candidatesOf(pw, legacy), Source.ENV)
        }

        // 2. OS keyring (CLI shell-out).
        if (keyring.isAvailable()) {
            keyring.read(KEYRING_SERVICE, KEYRING_ACCOUNT)?.takeIf { it.isNotEmpty() }?.let { existing ->
                return Result(existing, candidatesOf(existing, legacy), Source.KEYRING)
            }
            val fresh = random()
            if (keyring.store(KEYRING_SERVICE, KEYRING_ACCOUNT, fresh)) {
                logger("keystore: generated a new store password in the OS keyring")
                return Result(fresh, candidatesOf(fresh, legacy), Source.KEYRING)
            }
            logger("keystore: OS keyring unavailable for write; falling back to a 0600 password file")
        }

        // 3. 0600 password file fallback.
        val pwFile = File(baseDir, PASSWORD_FILENAME)
        readPasswordFile(pwFile)?.takeIf { it.isNotEmpty() }?.let { existing ->
            return Result(existing, candidatesOf(existing, legacy), Source.FILE)
        }
        val fresh = random()
        writePasswordFile(pwFile, fresh)
        logger("keystore: generated a new store password in ${pwFile.name} (0600)")
        return Result(fresh, candidatesOf(fresh, legacy), Source.FILE)
    }

    /** Primary first, legacy appended only if it differs — de-duplicated by content. */
    private fun candidatesOf(primary: CharArray, legacy: CharArray): List<CharArray> =
        if (primary.contentEquals(legacy)) listOf(primary) else listOf(primary, legacy)

    /** Reproduces the pre-H2 derivation so an existing store still opens for re-keying. */
    fun legacyDerivedPassword(): CharArray {
        val seed = buildString {
            append(System.getProperty("user.name").orEmpty())
            append('|')
            append(System.getProperty("user.home").orEmpty())
        }.ifBlank { "local-agent" }
        return seed.toCharArray()
    }

    /** ~256-bit URL-safe-Base64 password (ASCII → PBE-safe, matches the store's Base64 note). */
    fun generateRandomPassword(): CharArray {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toCharArray()
    }

    private fun defaultBackend(env: (String) -> String?): KeyringBackend = when {
        env(HEADLESS_ENV) == "1" -> NoKeyringBackend
        DesktopOs.isWindows -> NoKeyringBackend // no clean retrievable-secret CLI → file fallback
        else -> CliKeyringBackend()
    }

    private fun readPasswordFile(file: File): CharArray? =
        runCatching { if (file.isFile) file.readText().trim().toCharArray().takeIf { it.isNotEmpty() } else null }
            .getOrNull()

    private fun writePasswordFile(file: File, password: CharArray) {
        runCatching {
            file.parentFile?.mkdirs()
            val path = file.toPath()
            Files.deleteIfExists(path)
            // Create with 0600 up-front on POSIX so there's no world-readable window;
            // on a non-POSIX FS create normally then best-effort restrict.
            if (PosixPerms.isPosix(path)) {
                Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixPerms.FILE_0600))
            } else {
                Files.createFile(path)
            }
            file.writeText(String(password))
            PosixPerms.restrict(file, PosixPerms.FILE_0600)
        }
    }
}
