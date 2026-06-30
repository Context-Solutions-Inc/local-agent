package com.contextsolutions.localagent.ui.job

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contextsolutions.localagent.inference.DesktopLinkStatus
import com.contextsolutions.localagent.inference.DesktopLinkStatusProvider
import com.contextsolutions.localagent.job.Job
import com.contextsolutions.localagent.job.JobAdmin
import com.contextsolutions.localagent.job.JobBadge
import com.contextsolutions.localagent.job.JobCatalog
import com.contextsolutions.localagent.job.JobCatalogEntry
import com.contextsolutions.localagent.job.JobInitProgress
import com.contextsolutions.localagent.job.JobInitResult
import com.contextsolutions.localagent.job.JobInitStepInfo
import com.contextsolutions.localagent.job.JobInitializer
import com.contextsolutions.localagent.job.JobRepository
import com.contextsolutions.localagent.job.JobResync
import com.contextsolutions.localagent.job.JobScheduleType
import com.contextsolutions.localagent.job.RemoteJobRunner
import com.contextsolutions.localagent.platform.AppBuildConfig
import com.contextsolutions.localagent.sync.LastSyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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
 *    [com.contextsolutions.localagent.job.JobService] seam (see [JobAdmin]); on
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
    // Mobile-only (PR #85): the chat-header "unseen completed run" badge. Calling
    // [markSeen] when the screen is shown clears the dot. Null on desktop.
    private val badge: JobBadge? = null,
    // Desktop-only (PR #100): the bundled Choose Job catalog + the pre-save
    // initializer. Null on mobile → the Choose Job button is hidden.
    private val catalog: JobCatalog? = null,
    private val initializer: JobInitializer? = null,
    // Mobile-only (#39 follow-up): manual "re-sync jobs" — wipes the local job list and
    // force-pulls the desktop's current state. Null on desktop → the button is hidden.
    private val resync: JobResync? = null,
    // PR #70 diagnostics are gated behind this so a production packaged build stays
    // quiet (the form/save logs echo job names + commands). Matches DesktopDiag's
    // signal via the cross-platform AppBuildConfig seam. Read by JobsScreen too.
    buildConfig: AppBuildConfig,
) : ViewModel() {

    /** True on a debuggable/internal build — gates the [JobsScreen] form diagnostics. */
    val isDebug: Boolean = buildConfig.isDebug || buildConfig.isInternalBuild

    // Sorted by last-run time, most-recently-run first; jobs that have never run
    // (null lastRunAtEpochMs → Long.MIN_VALUE) sink to the bottom. The underlying
    // query is creation-order, and sortedWith is stable, so within the never-run
    // group the newest job lands last (a brand-new unrun job starts at the bottom).
    // Applies to both platforms (this VM is shared).
    val jobs: StateFlow<List<Job>> = repository.flow()
        .map { list -> list.sortedWith(compareByDescending { it.lastRunAtEpochMs ?: Long.MIN_VALUE }) }
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

    /** Desktop-only: the Choose Job catalog is available iff [catalog] + [initializer] are bound. */
    val canChooseJob: Boolean get() = catalog != null && initializer != null

    /** Mobile-only: the manual "re-sync jobs" button shows iff the [resync] seam is bound. */
    val canResync: Boolean get() = resync != null

    /** Load the bundled job catalog (PR #100). Empty when no catalog is bound. */
    suspend fun catalogEntries(): List<JobCatalogEntry> = catalog?.list().orEmpty()

    /** The ordered initialization steps for [entry] (PR #100) — shown as a checklist before running. */
    suspend fun planInit(entry: JobCatalogEntry): List<JobInitStepInfo> = initializer?.plan(entry).orEmpty()

    /**
     * Run [entry]'s pre-save initialization (PR #100), surfacing per-step progress via
     * [onProgress]. Returns the terminal result; the dialog enables Approve only on success.
     * Runs on the IO dispatcher inside the initializer; safe to call from the composition scope.
     */
    suspend fun initializeJob(entry: JobCatalogEntry, onProgress: (JobInitProgress) -> Unit): JobInitResult =
        initializer?.initialize(entry, onProgress) ?: JobInitResult.AlreadyInitialized

    /**
     * The user is looking at the Jobs screen — clear the chat-header unseen-completions
     * badge by advancing the "seen" watermark. No-op on desktop ([badge] null).
     * Called on entry and on each list change while the screen is open, so a run that
     * completes while it's visible doesn't leave a stale dot.
     */
    fun markSeen() {
        val b = badge ?: return
        viewModelScope.launch { b.markSeen() }
    }

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
            if (isDebug) println("[JobsVM] create ignored — no JobAdmin bound (mobile is read-only)")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (isDebug) println("[JobsVM] create name='${name.trim()}' command='${command.trim()}' schedule=$scheduleType cron=$cronExpression fireAt=$fireAtEpochMs")
            val job = a.create(name.trim(), command.trim(), prompt, workingDir, scheduleType, cronExpression, fireAtEpochMs)
            if (isDebug) println("[JobsVM] created job id=${job.id}")
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

    /**
     * Manual "re-sync jobs" (#39 follow-up): wipe the local job list, reset the sync
     * watermark, and force an immediate reconcile so the desktop's current job set
     * re-pulls. Online-only (gated like [runNow]/[cancel]); no-op on desktop ([resync] null).
     */
    fun resyncJobs() {
        val r = resync ?: return
        if (linkStatus.value != DesktopLinkStatus.UP) return
        viewModelScope.launch(Dispatchers.IO) { r.resyncFromDesktop() }
    }

    fun cancel(id: String) {
        val a = admin
        if (a != null) {
            // Desktop: cancel the local run directly (kills the process tree).
            a.cancel(id)
            return
        }
        // Mobile: ask the desktop over the link. Online-only (button is gated on
        // canControl, but guard here too).
        val r = remoteRunner ?: return
        if (linkStatus.value != DesktopLinkStatus.UP) return
        viewModelScope.launch(Dispatchers.IO) { r.cancel(id) }
    }
}
