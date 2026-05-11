package com.contextsolutions.mobileagent.app.di

import android.content.Context
import com.contextsolutions.mobileagent.app.BuildConfig
import com.contextsolutions.mobileagent.app.observability.HandlerMainThreadProbe
import com.contextsolutions.mobileagent.app.observability.MainThreadProbe
import com.contextsolutions.mobileagent.app.spike.StubInferenceEngine
import com.contextsolutions.mobileagent.inference.AndroidThermalStatusProvider
import com.contextsolutions.mobileagent.inference.DefaultHfAuthTokenProvider
import com.contextsolutions.mobileagent.inference.HfAuthTokenProvider
import com.contextsolutions.mobileagent.inference.InferenceEngine
import com.contextsolutions.mobileagent.inference.LiteRtInferenceEngineFactory
import com.contextsolutions.mobileagent.inference.ThermalStatusProvider
import com.contextsolutions.mobileagent.platform.SecureStorage
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds [InferenceEngine] to either the real LiteRT-LM-backed implementation or
 * the stub, controlled by [BuildConfig.USE_STUB_ENGINE]. Default is the real
 * engine; flip the flag in `androidApp/build.gradle.kts` (or via
 * `-PuseStubEngine=true`) when iterating on agent-loop / UI work where waiting
 * on a 4–8 s model load and 8 tok/s generation would slow you down.
 *
 * Both bindings live behind the same Hilt provider — the agent loop never knows
 * which one it's talking to. That's the whole point of the [InferenceEngine] seam.
 */
@Module
@InstallIn(SingletonComponent::class)
object InferenceModule {

    @Provides
    @Singleton
    fun provideInferenceEngine(@ApplicationContext context: Context): InferenceEngine =
        if (BuildConfig.USE_STUB_ENGINE) {
            StubInferenceEngine()
        } else {
            LiteRtInferenceEngineFactory.create(context)
        }

    /**
     * Binds the Android [ThermalStatusProvider] (M6 Phase B). Reads
     * `PowerManager.currentThermalStatus` and registers a thermal listener.
     * Phase B uses [ThermalStatusProvider.current] to gate the eager Gemma
     * load at SEVERE/CRITICAL; Phase E will use [ThermalStatusProvider.statusFlow]
     * for the chat banner / critical-state block per PRD §4.3.
     */
    @Provides
    @Singleton
    fun provideThermalStatusProvider(@ApplicationContext context: Context): ThermalStatusProvider =
        AndroidThermalStatusProvider(context)

    /**
     * Resolves the HuggingFace token used by `ModelDownloader` to authenticate
     * the gated Gemma 4 download. Same BYOK pattern as Brave Search:
     *  - user-supplied token from [SecureStorage] takes precedence
     *  - internal/debug builds fall back to `BuildConfig.HF_AUTH_TOKEN` so
     *    engineers don't have to configure their own token to exercise the
     *    download path
     *  - release builds always pass `null` for the dev fallback — production
     *    users provide their own token via onboarding or Settings
     */
    @Provides
    @Singleton
    fun provideHfAuthTokenProvider(secureStorage: SecureStorage): HfAuthTokenProvider {
        val devToken = if (BuildConfig.INTERNAL_BUILD) BuildConfig.HF_AUTH_TOKEN else null
        return DefaultHfAuthTokenProvider(secureStorage, devToken)
    }
}

/**
 * Companion `@Binds` module — keeps the `@Binds` interface-style provider
 * (required for [MainThreadProbe] → [HandlerMainThreadProbe]) separate from
 * the `object`-style providers above. The watchdog itself
 * ([com.contextsolutions.mobileagent.app.observability.MainThreadHeartbeatWatchdog])
 * has an `@Inject` constructor so Hilt resolves it automatically — no
 * explicit `@Provides` needed.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class WatchdogModule {

    @Binds
    @Singleton
    abstract fun bindMainThreadProbe(impl: HandlerMainThreadProbe): MainThreadProbe
}
