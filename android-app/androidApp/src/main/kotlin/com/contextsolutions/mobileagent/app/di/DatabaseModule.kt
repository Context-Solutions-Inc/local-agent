package com.contextsolutions.mobileagent.app.di

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.contextsolutions.mobileagent.db.MobileAgentDatabase
import com.contextsolutions.mobileagent.db.SearchCacheQueries
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
}
