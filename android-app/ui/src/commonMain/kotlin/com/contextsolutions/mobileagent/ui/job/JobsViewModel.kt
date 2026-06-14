package com.contextsolutions.mobileagent.ui.job

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contextsolutions.mobileagent.inference.DesktopLinkStatus
import com.contextsolutions.mobileagent.inference.DesktopLinkStatusProvider
import com.contextsolutions.mobileagent.job.Job
import com.contextsolutions.mobileagent.job.JobAdmin
import com.contextsolutions.mobileagent.job.JobRepository
import com.contextsolutions.mobileagent.job.JobScheduleType
import com.contextsolutions.mobileagent.job.RemoteJobRunner
import com.contextsolutions.mobileagent.sync.LastSyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * UI state for the Jobs screen (PR #70). The [repository] is the source of truth
 * on both platforms (mobile renders desktop-synced rows offline). [lastSyncStatus]
 * + [linkStatus] feed the "Synced 4m ago • Offline" header.
 *
 * Mutations differ by platform/connectivity:
 *  - **Pause/resume** is allowed on both platforms but only when the desktop link
 *    is UP (view-only offline — the toggle is disabled in the UI). The local write
 *    fires the change bus → pushes on the next reconcile → the desktop scheduler
 *    cancels/rearms.
 *  - **Create / edit / delete / run-now** are desktop-only and route through the
 *    [com.contextsolutions.mobileagent.job.JobService] seam (see [JobAdmin]); on
 *    mobile they're never surfaced.
 */
class JobsViewModel(
    private val repository: JobRepository,
    private val lastSyncStatus: LastSyncStatus,
    linkStatusProvider: DesktopLinkStatusProvider,
    private val admin: JobAdmin? = null,
    // Mobile-only: asks the desktop to run a job over the link (PR #84). Null on
    // desktop, where the local [admin] runs jobs directly.
    private val remoteRunner: RemoteJobRunner? = null,
) : ViewModel() {

    val jobs: StateFlow<List<Job>> = repository.flow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val lastSyncedAtMs: StateFlow<Long?> = lastSyncStatus.lastSyncedAtMs

    val linkStatus: StateFlow<DesktopLinkStatus> = linkStatusProvider.status

    /**
     * Pause/resume is actionable on the desktop (admin) always, and on mobile only
     * when the desktop link is reachable.
     */
    val canControl: Boolean
        get() = admin != null || linkStatus.value == DesktopLinkStatus.UP

    /** Desktop-only admin (create/edit/delete/run) is available iff [admin] is bound. */
    val isAdmin: Boolean get() = admin != null

    fun setPaused(id: String, paused: Boolean) {
        // Desktop (admin) always controls; mobile needs the link UP.
        if (admin == null && linkStatus.value != DesktopLinkStatus.UP) return
        viewModelScope.launch(Dispatchers.IO) {
            val a = admin
            if (a != null) {
                // Desktop: write the row AND drive the scheduler.
                a.setPaused(id, paused)
            } else {
                // Mobile: write locally + fire the change bus; the desktop scheduler
                // reacts when this pushes on the next reconcile.
                repository.setPaused(id, paused, Clock.System.now().toEpochMilliseconds())
            }
        }
    }

    fun create(
        name: String,
        command: String,
        prompt: String,
        workingDir: String?,
        scheduleType: JobScheduleType,
        cronExpression: String?,
        fireAtEpochMs: Long?,
    ) {
        val a = admin
        if (a == null) {
            println("[JobsVM] create ignored — no JobAdmin bound (mobile is read-only)")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            println("[JobsVM] create name='${name.trim()}' command='${command.trim()}' schedule=$scheduleType cron=$cronExpression fireAt=$fireAtEpochMs")
            val job = a.create(name.trim(), command.trim(), prompt, workingDir, scheduleType, cronExpression, fireAtEpochMs)
            println("[JobsVM] created job id=${job.id}")
        }
    }

    fun update(
        id: String,
        name: String,
        command: String,
        prompt: String,
        workingDir: String?,
        scheduleType: JobScheduleType,
        cronExpression: String?,
        fireAtEpochMs: Long?,
    ) {
        val a = admin ?: return
        viewModelScope.launch(Dispatchers.IO) {
            a.update(id, name.trim(), command.trim(), prompt, workingDir, scheduleType, cronExpression, fireAtEpochMs)
        }
    }

    fun delete(id: String) {
        val a = admin ?: return
        viewModelScope.launch(Dispatchers.IO) { a.delete(id) }
    }

    fun runNow(id: String) {
        val a = admin
        if (a != null) {
            // Desktop: run locally.
            a.runNow(id)
            return
        }
        // Mobile: ask the desktop over the link. Online-only (button is gated on
        // canControl, but guard here too).
        val r = remoteRunner ?: return
        if (linkStatus.value != DesktopLinkStatus.UP) return
        viewModelScope.launch(Dispatchers.IO) { r.runNow(id) }
    }
}
