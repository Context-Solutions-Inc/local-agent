package com.contextsolutions.mobileagent.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import com.contextsolutions.mobileagent.app.service.InferenceSessionManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MobileAgentApplication : Application() {

    @Inject
    lateinit var sessionManager: InferenceSessionManager

    override fun onCreate() {
        super.onCreate()
        // Auxiliary models (pre-flight classifier, memory extractor, embedder) will be
        // loaded here at app start in M3+ since their combined footprint is small enough
        // (PRD section 4.2). Gemma 4 is loaded lazily on first query, not here.
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
            sessionManager.forceUnload()
        }
    }

    private companion object {
        const val TAG = "MobileAgentApp"
    }
}
