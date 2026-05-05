package com.contextsolutions.mobileagent.app.di

import com.contextsolutions.mobileagent.app.service.AndroidInferenceForegroundServiceController
import com.contextsolutions.mobileagent.app.service.ForegroundServiceController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Bindings for the inference session lifecycle.
 *
 * [com.contextsolutions.mobileagent.app.service.InferenceSessionManager] is `@Inject` /
 * `@Singleton` on the class itself, so Hilt constructs it directly — no @Provides
 * needed. This module exists to bind the [ForegroundServiceController] interface
 * to its Android implementation; tests can substitute a fake without touching DI.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SessionModule {

    @Binds
    @Singleton
    abstract fun bindForegroundServiceController(
        impl: AndroidInferenceForegroundServiceController,
    ): ForegroundServiceController
}
