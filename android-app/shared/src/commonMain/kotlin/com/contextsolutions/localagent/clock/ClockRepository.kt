package com.contextsolutions.localagent.clock

import kotlinx.coroutines.flow.Flow

/**
 * Persistent store for user-set timers and alarms. Backed by SharedPreferences
 * + JSON on Android (mirrors [com.contextsolutions.localagent.language.LanguagePreferences]
 * and [com.contextsolutions.localagent.onboarding.OnboardingPreferences]) so
 * we don't pay the cost of a SQLDelight migration for what is at most a
 * handful of rows per user.
 *
 * Both flows emit the current snapshot on subscribe and on every mutation.
 */
interface ClockRepository {

    fun timers(): Flow<List<TimerEntry>>
    fun alarms(): Flow<List<AlarmEntry>>

    /** Snapshot reads — safe from any dispatcher, served from in-memory state. */
    fun snapshotTimers(): List<TimerEntry>
    fun snapshotAlarms(): List<AlarmEntry>

    fun upsertTimer(timer: TimerEntry)
    fun deleteTimer(id: String)

    fun upsertAlarm(alarm: AlarmEntry)
    fun deleteAlarm(id: String)
}
