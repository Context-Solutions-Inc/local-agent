package com.contextsolutions.localagent.clock

import com.contextsolutions.localagent.platform.DesktopJsonStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Desktop [ClockRepository] (docs/DESKTOP_PORT_PLAN.md, Phase 7), the
 * counterpart of Android's `SharedPreferencesClockRepository`. Timers and alarms
 * are persisted as JSON-serialised lists in a [DesktopJsonStore] file
 * ([KEY_TIMERS] / [KEY_ALARMS]) — the "handful of rows per user" the interface
 * doc calls out as not worth a SQLDelight migration.
 *
 * State is seeded from disk at construction; mutations update both the in-memory
 * [MutableStateFlow] (synchronous for subscribers) and the file (next-process
 * recovery). A corrupt/incompatible blob is discarded on load (worst case: one
 * round of stale alarms not re-armed), matching the Android impl.
 *
 * The persisted rows ARE the scheduler's source of truth: on app start the
 * desktop scheduler is empty (in-memory job registry), and
 * [ClockService.rearmAll] walks these rows to re-create the coroutine fires —
 * the desktop analogue of Android's boot-receiver re-arm.
 */
class DesktopClockRepository(private val store: DesktopJsonStore) : ClockRepository {

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
        store.getString(KEY_TIMERS)?.let { json.decodeFromString(timerSerializer, it) } ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    private fun loadAlarms(): List<AlarmEntry> = try {
        store.getString(KEY_ALARMS)?.let { json.decodeFromString(alarmSerializer, it) } ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    private fun persistTimers(list: List<TimerEntry>) {
        store.putString(KEY_TIMERS, json.encodeToString(timerSerializer, list))
    }

    private fun persistAlarms(list: List<AlarmEntry>) {
        store.putString(KEY_ALARMS, json.encodeToString(alarmSerializer, list))
    }

    private companion object {
        const val KEY_TIMERS = "timers_json"
        const val KEY_ALARMS = "alarms_json"
    }
}
