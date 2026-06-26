package com.contextsolutions.localagent.app.platform

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.contextsolutions.localagent.db.LocalAgentDatabase
import com.contextsolutions.localagent.platform.CleanBreakReset
import com.contextsolutions.localagent.platform.DatabaseKeyProvider
import com.contextsolutions.localagent.platform.SecureStorage
import com.contextsolutions.localagent.platform.SqliteHeader
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

/**
 * Builds the at-rest-encrypted SQLDelight driver (M1) — the Android counterpart of
 * `DesktopDatabaseFactory`. The SQLCipher passphrase comes from [SecureStorage]
 * ([DatabaseKeyProvider], Android Keystore-backed) and is fed into [AndroidSqliteDriver]
 * via net.zetetic's [SupportOpenHelperFactory] (an AndroidX `SupportSQLiteOpenHelper.Factory`),
 * so runtime schema create/migrate stays automatic.
 *
 * On open it also does a one-time fresh-start wipe of any legacy plaintext `local_agent.db`
 * (pre-M1 installs), and fails loudly if an encrypted DB exists but the key is gone (secure
 * store lost — never silently re-key a fresh DB over the unreadable data).
 */
object AndroidDatabaseFactory {
    fun create(
        context: Context,
        secureStorage: SecureStorage,
        dbName: String,
        logger: (String) -> Unit = {},
    ): SqlDriver {
        val dbFile = context.getDatabasePath(dbName)

        // L4 clean break: a pre-L4 install's androidx secrets (incl. the M1 DB key) are unreadable by
        // the new Keystore-direct store, so wipe the orphaned encrypted DB + legacy files BEFORE the
        // loss-guard below — otherwise it would error and brick launch. No-op on fresh/migrated installs.
        CleanBreakReset.run(context, secureStorage, dbFile, logger)

        if (dbFile.exists() && isPlaintextDb(dbFile)) {
            deleteDbFiles(dbFile)
            logger("wiped legacy plaintext DB (M1 fresh-start)")
        }
        if (dbFile.exists() && DatabaseKeyProvider.existing(secureStorage) == null) {
            error("Encrypted database present but no key in secure storage — secure store lost; refusing to re-key")
        }

        // sqlcipher-android does not auto-load its native lib; load it once before the factory.
        System.loadLibrary("sqlcipher")

        val passphrase = DatabaseKeyProvider.getOrCreate(secureStorage)
        val factory = SupportOpenHelperFactory(passphrase.encodeToByteArray())
        return AndroidSqliteDriver(LocalAgentDatabase.Schema, context, dbName, factory = factory)
    }

    /** True when [dbFile] starts with the plaintext SQLite magic (i.e. an unencrypted legacy DB). */
    private fun isPlaintextDb(dbFile: File): Boolean {
        val header = ByteArray(SqliteHeader.MAGIC_LENGTH)
        val read = dbFile.inputStream().use { it.read(header) }
        return read == SqliteHeader.MAGIC_LENGTH && SqliteHeader.isPlaintext(header)
    }

    /** Deletes the DB and its WAL/SHM/journal sidecars (best-effort). */
    private fun deleteDbFiles(dbFile: File) {
        listOf("", "-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
    }
}
