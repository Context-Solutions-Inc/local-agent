package com.contextsolutions.mobileagent.app.di

import com.contextsolutions.mobileagent.agent.AgentLoop
import com.contextsolutions.mobileagent.agent.PromptAssembler
import com.contextsolutions.mobileagent.agent.currentTimeContext
import com.contextsolutions.mobileagent.platform.AgentClock
import com.contextsolutions.mobileagent.platform.LocaleProvider
import com.contextsolutions.mobileagent.search.SearchService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the agent layer (prompt assembler, agent-loop factory) into Hilt.
 *
 * The function-call parser is no longer in the production path — the engine
 * surfaces tool calls via LiteRT-LM's structured callback (see
 * `LiteRtInferenceEngine.generate`), so there's nothing to parse out of text.
 * The marker parser stays in the codebase for tests and as a fallback if a
 * future model swap reverts to text-emitted tool calls.
 */
@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    @Provides
    @Singleton
    fun providePromptAssembler(
        clock: AgentClock,
        localeProvider: LocaleProvider,
    ): PromptAssembler = PromptAssembler(
        timeContextProvider = { currentTimeContext(clock, localeProvider) },
    )

    @Provides
    @Singleton
    fun provideAgentLoopFactory(
        assembler: PromptAssembler,
        searchService: SearchService,
    ): AgentLoopFactory = AgentLoopFactory { session ->
        AgentLoop(
            session = session,
            assembler = assembler,
            searchService = searchService,
        )
    }
}

fun interface AgentLoopFactory {
    fun create(session: com.contextsolutions.mobileagent.agent.InferenceSession): AgentLoop
}
