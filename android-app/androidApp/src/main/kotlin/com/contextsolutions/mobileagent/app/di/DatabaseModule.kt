package com.contextsolutions.mobileagent.app.di

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.contextsolutions.mobileagent.conversation.ConversationRepository
import com.contextsolutions.mobileagent.conversation.SqlDelightConversationRepository
import com.contextsolutions.mobileagent.db.ConversationsQueries
import com.contextsolutions.mobileagent.db.MobileAgentDatabase
import com.contextsolutions.mobileagent.db.SearchCacheQueries
import com.contextsolutions.mobileagent.db.TelemetryAggregateQueries
import com.contextsolutions.mobileagent.telemetry.TelemetryCounters
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Single [MobileAgentDatabase] instance per process, backed by the SQLDelight
 * Android driver. Per-table query handles are exposed as their own bindings so
 * consumers don't have to reach through the database object — that also keeps
 * mocks trivial when (rarely) needed.
 *
 * The DB file goes through the platform's standard data-at-rest encryption
 * (Android FBE Credential Encrypted Storage) per PRD §4.4 — no extra config
 * needed at the SQLDelight layer.
 *
 * Migrations run automatically: [AndroidSqliteDriver]'s default callback is
 * `Callback(schema)`, which routes `SQLiteOpenHelper.onUpgrade` to
 * `MobileAgentDatabase.Schema.migrate(driver, oldVersion, newVersion)`. The
 * generated migrate() applies the `.sqm` files under
 * `:shared/commonMain/sqldelight/com/contextsolutions/mobileagent/db/`
 * in version order (see `1.sqm` for the M6 Phase A v1 → v2 migration adding
 * `memories.access_count`). No explicit callback wiring needed here.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private const val DB_NAME = "mobile_agent.db"

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MobileAgentDatabase {
        val driver = AndroidSqliteDriver(MobileAgentDatabase.Schema, context, DB_NAME)
        return MobileAgentDatabase(driver)
    }

    @Provides
    @Singleton
    fun provideSearchCacheQueries(database: MobileAgentDatabase): SearchCacheQueries =
        database.searchCacheQueries

    @Provides
    @Singleton
    fun provideTelemetryAggregateQueries(database: MobileAgentDatabase): TelemetryAggregateQueries =
        database.telemetryAggregateQueries

    @Provides
    @Singleton
    fun provideConversationsQueries(database: MobileAgentDatabase): ConversationsQueries =
        database.conversationsQueries

    /**
     * PR#13 — wired up here so the conversation persistence + 8K-budget
     * enforcement gets the same DB instance as the rest of the persistence
     * layer. Per CLAUDE.md invariant #21 the constructor's ioDispatcher
     * default fires for production callers; tests can substitute.
     */
    @Provides
    @Singleton
    fun provideConversationRepository(
        queries: ConversationsQueries,
        counters: TelemetryCounters,
    ): ConversationRepository = SqlDelightConversationRepository(queries, counters)
}
