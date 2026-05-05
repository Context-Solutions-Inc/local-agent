package com.contextsolutions.mobileagent.app.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin abstraction over [Context.startService] / [Context.stopService] for the
 * inference foreground service. Lets [InferenceSessionManager] be unit-tested
 * without Robolectric or instrumentation — fakes implement the two methods.
 */
interface ForegroundServiceController {
    fun start()
    fun stop()
}

@Singleton
class AndroidInferenceForegroundServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
) : ForegroundServiceController {

    override fun start() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, InferenceForegroundService::class.java),
        )
    }

    override fun stop() {
        context.stopService(Intent(context, InferenceForegroundService::class.java))
    }
}
