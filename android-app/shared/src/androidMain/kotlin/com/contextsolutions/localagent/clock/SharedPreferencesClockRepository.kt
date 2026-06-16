package com.contextsolutions.localagent.clock

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Android [ClockRepository] backed by plain SharedPreferences + JSON. Mirrors
 * the pattern used by
 * [com.contextsolutions.localagent.language.SharedPreferencesLanguagePreferences]:
 *  - State seeded from disk at construction
 *  - Mutations update both the in-memory [MutableStateFlow] (so subscribers
 *    see the change synchronously) and SharedPreferences (so the next
 *    process recovers the same state)
 *  - Plain (non-encrypted) prefs: the data is "the user set an alarm for
 *    7am" — locale-like, not credential-like
 *
 * Schema is JSON-serialised lists keyed by [KEY_TIMERS] / [KEY_ALARMS]. A
 * format-version field isn't needed for now; if the data classes evolve
 * incompatibly, a try/catch around the initial deserialise discards the
 * stale blob and the user's worst case is one round of stale alarms not
 * showing up.
 */
class SharedPreferencesClockRepository(context: Context) : ClockRepository {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }
    private val timerSerializer = ListSerializer(TimerEntry.serializer())
    private val alarmSerializer = ListSerializer(AlarmEntry.serializer())

    private val timersState = MutableStateFlow(loadTimers())
    private val alarmsState = MutableStateFlow(loadAlarms())

    override fun timers(): Flow<List<TimerEntry>> = timersState.asStateFlow()
    override fun alarms(): Flow<List<AlarmEntry>> = alarmsState.asStateFlow()

    override fun snapshotTimers(): List<TimerEntry> = timersState.value
    override fun snapshotAlarms(): List<AlarmEntry> = alarmsState.value

    override fun upsertTimer(timer: TimerEntry) {
        val updated = timersState.value.filterNot { it.id == timer.id } + timer
        timersState.value = updated
        persistTimers(updated)
    }

    override fun deleteTimer(id: String) {
        val updated = timersState.value.filterNot { it.id == id }
        if (updated.size == timersState.value.size) return
        timersState.value = updated
        persistTimers(updated)
    }

    override fun upsertAlarm(alarm: AlarmEntry) {
        val updated = alarmsState.value.filterNot { it.id == alarm.id } + alarm
        alarmsState.value = updated
        persistAlarms(updated)
    }

    override fun deleteAlarm(id: String) {
        val updated = alarmsState.value.filterNot { it.id == id }
        if (updated.size == alarmsState.value.size) return
        alarmsState.value = updated
        persistAlarms(updated)
    }

    private fun loadTimers(): List<TimerEntry> = try {
        val raw = prefs.getString(KEY_TIMERS, null) ?: return emptyList()
        json.decodeFromString(timerSerializer, raw)
    } catch (_: Throwable) {
        emptyList()
    }

    private fun loadAlarms(): List<AlarmEntry> = try {
        val raw = prefs.getString(KEY_ALARMS, null) ?: return emptyList()
        json.decodeFromString(alarmSerializer, raw)
    } catch (_: Throwable) {
        emptyList()
    }

    private fun persistTimers(list: List<TimerEntry>) {
        prefs.edit().putString(KEY_TIMERS, json.encodeToString(timerSerializer, list)).apply()
    }

    private fun persistAlarms(list: List<AlarmEntry>) {
        prefs.edit().putString(KEY_ALARMS, json.encodeToString(alarmSerializer, list)).apply()
    }

    private companion object {
        const val PREFS_NAME = "clock_prefs"
        const val KEY_TIMERS = "timers_json"
        const val KEY_ALARMS = "alarms_json"
    }
}
