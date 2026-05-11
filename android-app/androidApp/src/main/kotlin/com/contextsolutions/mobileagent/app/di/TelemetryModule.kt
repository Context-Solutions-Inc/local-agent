package com.contextsolutions.mobileagent.app.di

import android.content.Context
import com.contextsolutions.mobileagent.db.TelemetryAggregateQueries
import com.contextsolutions.mobileagent.observability.FirebaseSafeCrashReporter
import com.contextsolutions.mobileagent.observability.SafeCrashReporter
import com.contextsolutions.mobileagent.telemetry.AnalyticsSink
import com.contextsolutions.mobileagent.telemetry.FirebaseAnalyticsSink
import com.contextsolutions.mobileagent.telemetry.InMemoryTelemetryCounters
import com.contextsolutions.mobileagent.telemetry.SharedPreferencesTelemetryConsentManager
import com.contextsolutions.mobileagent.telemetry.TelemetryConsentManager
import com.contextsolutions.mobileagent.telemetry.TelemetryCounters
import com.contextsolutions.mobileagent.telemetry.TelemetryFlusher
import com.contextsolutions.mobileagent.telemetry.TelemetryPayloadBuilder
import com.contextsolutions.mobileagent.telemetry.TelemetryUploader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * M6 Phase C — opt-in aggregate telemetry pipeline.
 *
 * Bindings:
 *  - [TelemetryConsentManager] (this file): user-facing toggle + first-run flag.
 *  - `TelemetryCounters` (future): in-memory counter registry, flushed to SQL.
 *  - `TelemetryPayloadBuilder` (future): reads aggregate tables, produces
 *    Firebase event payloads. Unit-tested to never touch memories/messages.
 *  - `TelemetryUploader` (future): WorkManager periodic worker.
 *  - `AnalyticsSink` (future): abstraction over FirebaseAnalytics; lets
 *    tests inject a fake without firebase-test-lab on the classpath.
 */
@Module
@InstallIn(SingletonComponent::class)
object TelemetryModule {

    @Provides
    @Singleton
    fun provideTelemetryConsentManager(
        @ApplicationContext context: Context,
    ): TelemetryConsentManager = SharedPreferencesTelemetryConsentManager(context)

    /**
     * Singleton in-memory counter registry; bound to both interfaces so the
     * recording (TelemetryCounters) and persistence (TelemetryFlusher) sides
     * share state via the same instance.
     */
    @Provides
    @Singleton
    fun provideInMemoryTelemetryCounters(
        queries: TelemetryAggregateQueries,
    ): InMemoryTelemetryCounters = InMemoryTelemetryCounters(queries)

    @Provides
    @Singleton
    fun provideTelemetryCounters(
        impl: InMemoryTelemetryCounters,
    ): TelemetryCounters = impl

    @Provides
    @Singleton
    fun provideTelemetryFlusher(
        impl: InMemoryTelemetryCounters,
    ): TelemetryFlusher = impl

    @Provides
    @Singleton
    fun provideAnalyticsSink(@ApplicationContext context: Context): AnalyticsSink =
        FirebaseAnalyticsSink(context)

    @Provides
    @Singleton
    fun provideTelemetryPayloadBuilder(
        queries: TelemetryAggregateQueries,
    ): TelemetryPayloadBuilder = TelemetryPayloadBuilder(queries)

    @Provides
    @Singleton
    fun provideTelemetryUploader(
        consent: TelemetryConsentManager,
        flusher: TelemetryFlusher,
        builder: TelemetryPayloadBuilder,
        sink: AnalyticsSink,
        queries: TelemetryAggregateQueries,
    ): TelemetryUploader = TelemetryUploader(
        consent = consent,
        flusher = flusher,
        builder = builder,
        sink = sink,
        queries = queries,
        nowEpochMs = { System.currentTimeMillis() },
    )

    /**
     * M6 Phase D — Firebase Crashlytics facade. Every callsite that wants
     * to record an exception or breadcrumb goes through this single
     * binding so [com.contextsolutions.mobileagent.observability.ContentRedactor]
     * scrubs the payload before it reaches the SDK. Crashlytics's own
     * collection toggle is bound to the consent flow from
     * [com.contextsolutions.mobileagent.app.MobileAgentApplication.onCreate].
     */
    @Provides
    @Singleton
    fun provideSafeCrashReporter(): SafeCrashReporter = FirebaseSafeCrashReporter()
}
