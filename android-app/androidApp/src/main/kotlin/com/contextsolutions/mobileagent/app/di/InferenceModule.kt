package com.contextsolutions.mobileagent.app.di

import android.content.Context
import com.contextsolutions.mobileagent.app.BuildConfig
import com.contextsolutions.mobileagent.app.spike.StubInferenceEngine
import com.contextsolutions.mobileagent.inference.InferenceEngine
import com.contextsolutions.mobileagent.inference.LiteRtInferenceEngineFactory
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
}
