package com.contextsolutions.mobileagent.app

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Bundle
import android.util.Log
import com.contextsolutions.mobileagent.app.observability.MainThreadHeartbeatWatchdog
import com.contextsolutions.mobileagent.app.observability.MemoryPressureWatchdog
import com.contextsolutions.mobileagent.app.observability.SystemMemoryMonitor
import com.contextsolutions.mobileagent.app.service.AuxModelLifecycleCoordinator
import com.contextsolutions.mobileagent.app.service.InferenceSessionManager
import com.contextsolutions.mobileagent.app.service.TelemetryUploadWorker
import com.contextsolutions.mobileagent.app.service.UnloadReason
import com.contextsolutions.mobileagent.app.di.androidModule
import com.contextsolutions.mobileagent.di.agentCoreModule
import com.contextsolutions.mobileagent.ui.di.uiModule
import com.contextsolutions.mobileagent.inference.OllamaConnectionMonitor
import com.contextsolutions.mobileagent.job.JobCompletionNotifier
import com.contextsolutions.mobileagent.preferences.DesktopLinkPreferences
import com.contextsolutions.mobileagent.sync.SyncController
import com.contextsolutions.mobileagent.observability.SafeCrashReporter
import com.contextsolutions.mobileagent.preferences.OllamaPreferences
import com.contextsolutions.mobileagent.telemetry.TelemetryConsentManager
import com.contextsolutions.mobileagent.telemetry.TelemetryFlusher
import com.google.firebase.analytics.FirebaseAnalytics
import org.koin.android.ext.android.inject
import org.koin.core.qualifier.named
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch

class MobileAgentApplication : Application() {

    // Phase 3: Hilt is gone — these resolve lazily from Koin (started in onCreate before
    // they're first touched). All are process singletons in `androidModule`.
    private val sessionManager: InferenceSessionManager by inject()
    private val auxModelCoordinator: AuxModelLifecycleCoordinator by inject()
    private val telemetryConsent: TelemetryConsentManager by inject()
    private val telemetryFlusher: TelemetryFlusher by inject()
    private val crashReporter: SafeCrashReporter by inject()
    private val mainThreadWatchdog: MainThreadHeartbeatWatchdog by inject()
    private val memoryPressureWatchdog: MemoryPressureWatchdog by inject()
    private val systemMemoryMonitor: SystemMemoryMonitor by inject()
    private val ollamaPreferences: OllamaPreferences by inject()
    private val ollamaConnectionMonitor: OllamaConnectionMonitor by inject()
    private val desktopLinkPreferences: DesktopLinkPreferences by inject()
    private val desktopLinkConnectionMonitor: OllamaConnectionMonitor by inject(named("desktopLink"))
    private val syncController: SyncController by inject()
    private val jobCompletionNotifier: JobCompletionNotifier by inject()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Foreground-only job-completion observer (PR #85); cancelled when backgrounded. */
    private var jobNotifierJob: Job? = null

    /**
     * Count of currently-`onStart`'d activities. The watchdog runs only
     * while > 0 (i.e., process is foregrounded). Cheaper than adding the
     * `androidx.lifecycle:lifecycle-process` dep just for [ProcessLifecycleOwner].
     */
    private var startedActivityCount: Int = 0

    override fun onCreate() {
        // Desktop-port migration (docs/DESKTOP_PORT_PLAN.md Phase 3): Koin owns the whole
        // graph. Start it before our `by inject()` fields are first touched below. Guarded
        // so instrumentation tests that re-create the Application don't trip
        // "KoinApplication already started".
        if (GlobalContext.getOrNull() == null) {
            startKoin {
                androidContext(this@MobileAgentApplication)
                modules(agentCoreModule, androidModule, uiModule)
            }
        }

        super.onCreate()
        // Auxiliary models (pre-flight classifier, memory extractor, embedder) will be
        // loaded here at app start in M3+ since their combined footprint is small enough
        // (PRD section 4.2). Gemma 4 is loaded lazily on first query, not here.

        // M6 Phase C + D — telemetry + crash-reporting orchestration.
        //
        // (a) Bind Firebase Analytics + Crashlytics collection to the
        // user's consent. Both SDKs have their own internal collection
        // (session_start, crash auto-capture) that runs independently of
        // our code. Without these flips, opting out wouldn't actually
        // stop the SDKs from phoning home for their own bookkeeping.
        // PRD §3.2.1 + §4.4: opt-in only, no implicit telemetry.
        val firebase = FirebaseAnalytics.getInstance(this)
        telemetryConsent.enabledFlow()
            .distinctUntilChanged()
            .onEach { enabled ->
                firebase.setAnalyticsCollectionEnabled(enabled)
                crashReporter.setCollectionEnabled(enabled)
            }
            .launchIn(appScope)

        // (b) Install our redacting uncaught-exception handler. Chains
        // through to whatever Crashlytics installed (or the default
        // platform handler if Crashlytics is opted out). PRD §4.4: any
        // crash report must scrub message + memory content.
        installRedactingUncaughtExceptionHandler()

        // (c) Schedule the periodic upload worker. KEEP policy means
        // existing schedules are preserved across app restarts; this is
        // safe to call every onCreate.
        TelemetryUploadWorker.schedule(this)

        // (d) M7 — main-thread responsiveness watchdog. Pre-empts the
        // GPU-saturation soft-reboot failure mode (see crash analysis in
        // the watchdog PR description). Gated on foreground via the
        // activity-lifecycle callbacks below so we don't fire false
        // positives while the OS parks the main thread in background.
        registerActivityLifecycleCallbacks(WatchdogForegroundGate())

        // (e) PR #16 — memory-pressure watchdog. Runs for the process
        // lifetime (no foreground gate): pressure can build while we're
        // backgrounded too, and the OS is more aggressive about reclaiming
        // a backgrounded app holding 3 GB resident. The watchdog only
        // does work while [SessionState.Loaded] — flips to Unloaded cancel
        // its inner polling loop via collectLatest semantics.
        memoryPressureWatchdog.start()

        // (f) PR #18 — system-memory status monitor. Same MemoryHeadroomProvider
        // as the watchdog but polls unconditionally so the chat-header status
        // dot reflects device state regardless of whether Gemma is resident.
        systemMemoryMonitor.start()

        // (g) PR #56 — drop a resident model whenever the Ollama server config
        // changes so the next turn re-decides the backend (remote ↔ local).
        // `drop(1)` skips the replayed current value at startup (nothing is
        // loaded yet); forceUnload(Manual) defers if a generation is in flight.
        ollamaPreferences.configFlow()
            .drop(1)
            .distinctUntilChanged()
            .onEach { sessionManager.forceUnload(reason = UnloadReason.Manual) }
            .launchIn(appScope)

        // (h) PR #56 — recover from a remote Ollama server going offline/online:
        // the monitor asks us to drop the resident handle so the next turn
        // re-decides remote↔local (fall back when it dies, reconnect when it
        // returns). forceUnload(Manual) defers if a generation is in flight.
        ollamaConnectionMonitor.reloadRequests
            .onEach { sessionManager.forceUnload(reason = UnloadReason.Manual) }
            .launchIn(appScope)

        // (i) PR #57 — same two hooks for the mobile↔desktop link: drop the
        // resident model when the link config changes (toggle / re-pair) and when
        // the desktop-link monitor reports the desktop went offline/online, so the
        // next turn re-decides desktop-link ↔ Ollama ↔ local.
        desktopLinkPreferences.configFlow()
            .drop(1)
            .distinctUntilChanged()
            .onEach { sessionManager.forceUnload(reason = UnloadReason.Manual) }
            .launchIn(appScope)
        desktopLinkConnectionMonitor.reloadRequests
            .onEach { sessionManager.forceUnload(reason = UnloadReason.Manual) }
            .launchIn(appScope)

        // (j) PR #57 — start the bidirectional sync orchestrator. It idles until
        // the link is enabled + paired, then reconciles conversations + memories
        // with the desktop (on-connect, on local change, and via the desktop's
        // change-SSE).
        syncController.start()
    }

    /**
     * Counts onStart/onStop activity transitions and toggles the
     * [MainThreadHeartbeatWatchdog] accordingly. Same primitive as
     * [androidx.lifecycle.ProcessLifecycleOwner] — keeping it inline
     * avoids adding the `lifecycle-process` artifact for this single use.
     *
     * All callbacks run on the main thread, so [startedActivityCount]
     * doesn't need synchronisation.
     */
    private inner class WatchdogForegroundGate : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) {
            if (startedActivityCount == 0) {
                mainThreadWatchdog.start()
                // PR #85 — observe synced job completions only while foregrounded
                // (matches mobile sync, which also runs foreground). Re-start fresh
                // so each foreground session re-baselines + re-dedupes.
                jobNotifierJob = jobCompletionNotifier.start(appScope)
            }
            startedActivityCount++
        }
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) {
            startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
            if (startedActivityCount == 0) {
                mainThreadWatchdog.stop()
                jobNotifierJob?.cancel()
                jobNotifierJob = null
            }
        }
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    /**
     * Wrap whatever uncaught-exception handler Crashlytics installed
     * (Crashlytics installs its own at SDK init time) with a layer that
     * runs [SafeCrashReporter.recordException] first — that path applies
     * [com.contextsolutions.mobileagent.observability.ContentRedactor]
     * to the throwable before forwarding. The chained handler still
     * gets called so Crashlytics's own crash-capture pipeline runs
     * unchanged. Net effect: any unhandled crash arrives at Crashlytics
     * twice (once as a redacted recordException, once as the raw
     * uncaught-exception capture), but the SDK dedups same-process
     * crashes within a session.
     *
     * Trade-off accepted: the duplicate is preferable to skipping
     * Crashlytics's auto-capture entirely — without that path, we'd
     * lose JNI/native crash surface coverage. v1.x could revisit
     * once we have telemetry on real crash signatures.
     */
    private fun installRedactingUncaughtExceptionHandler() {
        val existing = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { crashReporter.recordException(throwable) }
            existing?.uncaughtException(thread, throwable)
        }
    }

    override fun onTerminate() {
        // (c) Session-end flush — drain the in-memory recorder before the
        // process dies. Best-effort: onTerminate is documented as never
        // called on production builds (emulator/test only), but a flush
        // here is still useful for dev iteration. Real "session-end" on
        // production is handled by the next worker fire reading the SQL
        // tables.
        appScope.launch { telemetryFlusher.flush() }
        super.onTerminate()
    }

    /**
     * Free Gemma's ~3.5 GB of resident memory under system pressure (PHASE1_PLAN §6,
     * M0_DECISION_MEMO Risk row "8 GB RAM headroom too tight"). Cold reload at 4–8 s
     * is acceptable; getting LMKD-killed is not.
     *
     * `RUNNING_CRITICAL` (15) means "foreground but the system is critically low";
     * higher values are background trims of increasing severity. We treat all of them
     * the same — drop the model. The 5-minute idle timer would eventually reclaim the
     * memory anyway, but on a multi-app workflow we want to react to OS pressure
     * immediately.
     *
     * Android 14 deprecated the trim-level constants (advice: "rely on the OS"), but
     * for an app holding 3.5 GB resident on an 8 GB device, ignoring the signal would
     * mean waiting for LMKD instead. PHASE1_PLAN §6 explicitly mandates this hook;
     * suppressing the warning is intentional.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            Log.w(TAG, "onTrimMemory level=$level — requesting model unload.")
            sessionManager.forceUnload(reason = UnloadReason.TrimMemory)
            // PR #8 — release the classifier (~68 MB) + embedder (~24 MB)
            // alongside Gemma's 3.5 GB. Same trigger, same UnloadReason.
            auxModelCoordinator.forceUnload(UnloadReason.TrimMemory)
        }
    }

    private companion object {
        const val TAG = "MobileAgentApp"
    }
}
