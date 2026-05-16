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
import com.contextsolutions.mobileagent.observability.SafeCrashReporter
import com.contextsolutions.mobileagent.telemetry.TelemetryConsentManager
import com.contextsolutions.mobileagent.telemetry.TelemetryFlusher
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch

@HiltAndroidApp
class MobileAgentApplication : Application() {

    @Inject
    lateinit var sessionManager: InferenceSessionManager

    @Inject
    lateinit var auxModelCoordinator: AuxModelLifecycleCoordinator

    @Inject
    lateinit var telemetryConsent: TelemetryConsentManager

    @Inject
    lateinit var telemetryFlusher: TelemetryFlusher

    @Inject
    lateinit var crashReporter: SafeCrashReporter

    @Inject
    lateinit var mainThreadWatchdog: MainThreadHeartbeatWatchdog

    @Inject
    lateinit var memoryPressureWatchdog: MemoryPressureWatchdog

    @Inject
    lateinit var systemMemoryMonitor: SystemMemoryMonitor

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Count of currently-`onStart`'d activities. The watchdog runs only
     * while > 0 (i.e., process is foregrounded). Cheaper than adding the
     * `androidx.lifecycle:lifecycle-process` dep just for [ProcessLifecycleOwner].
     */
    private var startedActivityCount: Int = 0

    override fun onCreate() {
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
            }
            startedActivityCount++
        }
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) {
            startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
            if (startedActivityCount == 0) {
                mainThreadWatchdog.stop()
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
