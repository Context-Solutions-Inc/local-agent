package com.contextsolutions.localagent.ui.clock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contextsolutions.localagent.clock.AlarmDay
import com.contextsolutions.localagent.clock.AlarmEntry
import com.contextsolutions.localagent.clock.ClockRepository
import com.contextsolutions.localagent.clock.ClockService
import com.contextsolutions.localagent.clock.TimerEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Surface state for the clock UI. The repository's StateFlows are the source
 * of truth; this view-model just exposes them on viewModelScope and forwards
 * user actions to [ClockService] (writes happen on [Dispatchers.IO] so the
 * SharedPreferences encode/commit doesn't touch the main thread).
 */
class ClockViewModel(
    private val clockService: ClockService,
    repository: ClockRepository,
) : ViewModel() {

    val timers: StateFlow<List<TimerEntry>> = repository.timers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), repository.snapshotTimers())

    val alarms: StateFlow<List<AlarmEntry>> = repository.alarms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), repository.snapshotAlarms())

    fun createTimer(durationMs: Long, label: String?) {
        if (durationMs <= 0) return
        viewModelScope.launch(Dispatchers.IO) {
            clockService.createTimer(durationMs, label)
        }
    }

    fun extendTimer(id: String, extraMs: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            clockService.extendTimer(id, extraMs)
        }
    }

    fun cancelTimer(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            clockService.cancelTimer(id)
        }
    }

    fun createAlarm(hour: Int, minute: Int, days: Set<AlarmDay>, label: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            clockService.createAlarm(hour, minute, days, label)
        }
    }

    fun updateAlarm(alarm: AlarmEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            clockService.updateAlarm(alarm)
        }
    }

    fun cancelAlarm(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            clockService.cancelAlarm(id)
        }
    }

    fun setAlarmEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            clockService.setAlarmEnabled(id, enabled)
        }
    }
}
