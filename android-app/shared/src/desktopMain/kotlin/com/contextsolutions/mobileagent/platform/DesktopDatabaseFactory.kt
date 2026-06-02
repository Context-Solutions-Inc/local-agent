package com.contextsolutions.mobileagent.platform

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.contextsolutions.mobileagent.db.MobileAgentDatabase
import com.contextsolutions.mobileagent.inference.DesktopAppDirs
import java.io.File
import java.util.Properties

/**
 * File-backed SQLDelight driver for desktop (Phase 6, docs/DESKTOP_PORT_PLAN.md).
 * Replaces the Phase-2 in-memory `JdbcSqliteDriver(IN_MEMORY)` so conversations,
 * memories, the search cache, clock/todos and telemetry survive across launches —
 * the desktop counterpart of Android's `AndroidSqliteDriver` over `mobile_agent.db`.
 *
 * SQLDelight 2.0.2's `JdbcSqliteDriver` has no schema-aware constructor, so we
 * drive create/migrate off SQLite's `PRAGMA user_version` ourselves (what
 * `AndroidSqliteDriver` does internally via its `SupportSQLiteOpenHelper`):
 *  - fresh file (version 0) → `Schema.create` + stamp `Schema.version`;
 *  - older version → `Schema.migrate` (walks the committed `.sqm` files) + restamp;
 *  - current → open as-is.
 *
 * The DB lives at `<app-data>/mobile_agent.db` ([DesktopAppDirs]); the same file
 * the Phase-7 tray/UI and any background task queue read.
 */
object DesktopDatabaseFactory {
    const val DB_FILENAME: String = "mobile_agent.db"

    fun create(baseDir: File = DesktopAppDirs.dataDir(), logger: (String) -> Unit = {}): SqlDriver {
        baseDir.mkdirs()
        val dbFile = File(baseDir, DB_FILENAME)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", Properties())
        val schema = MobileAgentDatabase.Schema
        when (val current = userVersion(driver)) {
            0L -> {
                schema.create(driver)
                setUserVersion(driver, schema.version)
                logger("created schema v${schema.version} at $dbFile")
            }
            in 1 until schema.version -> {
                schema.migrate(driver, current, schema.version)
                setUserVersion(driver, schema.version)
                logger("migrated schema $current → ${schema.version} at $dbFile")
            }
            else -> logger("opened schema v$current at $dbFile")
        }
        return driver
    }

    private fun userVersion(driver: SqlDriver): Long =
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA user_version",
            mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
            parameters = 0,
        ).value ?: 0L

    private fun setUserVersion(driver: SqlDriver, version: Long) {
        // PRAGMA doesn't accept bound parameters; version is a trusted Long.
        driver.execute(identifier = null, sql = "PRAGMA user_version = $version", parameters = 0)
    }
}
