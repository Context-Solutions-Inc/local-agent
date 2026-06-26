package com.contextsolutions.localagent.platform

import android.content.Context
import java.io.File

/**
 * One-time graceful reset for the L4 clean break (androidx `security-crypto` → Keystore-direct,
 * with **no read-old migration**).
 *
 * On an in-place upgrade from a pre-L4 build, the new [SecureStorage] starts empty — it cannot read
 * the old androidx `EncryptedSharedPreferences`/`EncryptedFile` data — so the M1 SQLCipher key is
 * gone while the encrypted `local_agent.db` is still on disk. Left alone, `AndroidDatabaseFactory`'s
 * keystore-loss guard would `error(...)` and brick launch. This detects that exact pre-L4 state (a
 * legacy androidx artifact present **and** no key in the new store) and wipes the orphaned encrypted
 * DB plus the legacy files so a fresh key + DB are minted — the accepted clean-break outcome
 * (re-pair + re-enter API keys), minus the crash.
 *
 * It **only deletes files** (never reads androidx), so the dependency still fully goes away. After
 * the legacy artifacts are gone the normal loud loss-guard resumes protecting against a genuine
 * future Keystore loss. Idempotent: once the new store holds a key, [reset] is a no-op.
 */
object CleanBreakReset {
    /** Legacy androidx `EncryptedSharedPreferences` file (without the `.xml` suffix). */
    private const val LEGACY_PREFS_NAME = "local_agent_secure_prefs"

    /** Legacy androidx `EncryptedFile` that held the relay X25519 identity. */
    private const val LEGACY_IDENTITY_NAME = "relay_identity.x25519.enc"

    fun run(context: Context, secureStorage: SecureStorage, dbFile: File, logger: (String) -> Unit = {}) {
        val legacyPrefsXml = File(File(context.applicationInfo.dataDir, "shared_prefs"), "$LEGACY_PREFS_NAME.xml")
        val legacyIdentity = File(context.filesDir, LEGACY_IDENTITY_NAME)
        reset(
            legacyPrefsXml = legacyPrefsXml,
            legacyIdentity = legacyIdentity,
            dbFile = dbFile,
            hasDbKey = DatabaseKeyProvider.existing(secureStorage) != null,
            logger = logger,
        )
    }

    /**
     * Pure core (no `Context`, so it unit-tests from `:androidApp` against temp dirs — hence public,
     * not internal, since that test is a separate Gradle module). Returns true when a reset fired.
     * Fires only when the new store has no DB key **and** a legacy androidx artifact is present —
     * i.e. a pre-L4 install whose secrets are now unreadable. A genuine fresh install (no legacy
     * files) or an already-migrated install (key present) is left untouched.
     */
    fun reset(
        legacyPrefsXml: File,
        legacyIdentity: File,
        dbFile: File,
        hasDbKey: Boolean,
        logger: (String) -> Unit = {},
    ): Boolean {
        if (hasDbKey) return false
        if (!legacyPrefsXml.exists() && !legacyIdentity.exists()) return false

        legacyPrefsXml.delete()
        legacyIdentity.delete()
        listOf("", "-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
        logger("clean-break (L4): cleared legacy androidx store + orphaned encrypted DB; re-pair + re-enter keys required")
        return true
    }
}
