package com.contextsolutions.localagent.ui.chat

import com.contextsolutions.localagent.agent.AgentEvent
import com.contextsolutions.localagent.agent.AgentLoop
import com.contextsolutions.localagent.agent.AgentTurnInput
import com.contextsolutions.localagent.agent.TranslationIntentDetector
import com.contextsolutions.localagent.di.AgentLoopFactory
import com.contextsolutions.localagent.agent.ChatLogger
import com.contextsolutions.localagent.agent.ChatSessionController
import com.contextsolutions.localagent.inference.SessionState
import com.contextsolutions.localagent.conversation.ConversationRepository
import com.contextsolutions.localagent.inference.Accelerator
import com.contextsolutions.localagent.inference.ThermalStatus
import com.contextsolutions.localagent.inference.ThermalStatusProvider
import com.contextsolutions.localagent.language.LanguagePreferences
import com.contextsolutions.localagent.memory.MemoryExtractor
import com.contextsolutions.localagent.memory.MemoryStore
import com.contextsolutions.localagent.telemetry.NoOpTelemetryCounters
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PR #22 — locks the contract on `ChatViewModel.cancel()`'s two-stage UX:
 *
 *  - Tapping Cancel flips `isCancelling = true` synchronously so the
 *    button can immediately render "Cancelling…" and disable itself.
 *    Without this, the button stays active during the (tens-of-ms to
 *    hundreds-of-ms) window between Job.cancel() and the coroutine
 *    actually unwinding, and the user re-taps thinking it didn't work.
 *  - The CancellationException catch in send() clears both
 *    `isCancelling` and `isGenerating` and wipes partialText.
 *  - cancel() is a no-op when there's nothing to cancel — won't
 *    surface a confusing "Cancelling…" state on a chat surface that
 *    isn't currently generating.
 *
 * The native-side cancel (`Conversation.cancelProcess()` inside
 * `LiteRtInferenceEngine`) cannot be exercised at the unit-test level
 * because LiteRT-LM types aren't mockable from common test fixtures;
 * it's verified by on-device manual testing per the PR test plan.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelCancelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val agentLoopFactory: AgentLoopFactory = mockk()
    private val sessionController: ChatSessionController = mockk()
    private val languagePreferences: LanguagePreferences = mockk(relaxed = true)
    private val translationIntentDetector: TranslationIntentDetector = mockk(relaxed = true)
    private val memoryExtractor: MemoryExtractor = mockk(relaxed = true)
    private val memoryStore: MemoryStore = mockk(relaxed = true)
    private val conversationRepository: ConversationRepository = mockk(relaxed = true)
    private val thermalProvider: ThermalStatusProvider = mockk(relaxed = true)

    private val sessionStateFlow = MutableStateFlow<SessionState>(SessionState.Loaded(Accelerator.GPU))

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { sessionController.state } returns sessionStateFlow
        coEvery { sessionController.newSession() } returns mockk(relaxed = true)
        every { thermalProvider.statusFlow() } returns flowOf(ThermalStatus.NONE)
        every { thermalProvider.current() } returns ThermalStatus.NONE
        every { translationIntentDetector.isTranslationRequest(any(), any()) } returns false
        coEvery { conversationRepository.appendMessage(any(), any(), any()) } returns 0L
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `cancel flips isCancelling true synchronously then clears on unwind`() = runTest {
        // A turn that hangs forever until we cancel it — simulates the
        // "Gemma is happily decoding tokens, user wants out NOW" path.
        val neverComplete = CompletableDeferred<Unit>()
        val mockLoop: AgentLoop = mockk()
        every { mockLoop.run(any<AgentTurnInput>()) } returns flow {
            // Stream one chunk so the UI is unambiguously mid-turn.
            emit(AgentEvent.TokenChunk("hello"))
            // Wait forever (the test cancels this).
            neverComplete.await()
        }
        every { agentLoopFactory.create(any(), any(), any()) } returns mockLoop

        val vm = newViewModel()
        vm.send("tell me a story")
        advanceUntilIdle()
        // Mid-turn: button is visible, no cancel pending.
        assertTrue("expected isGenerating mid-turn", vm.ui.value.isGenerating)
        assertFalse("isCancelling must be false before cancel tap", vm.ui.value.isCancelling)

        vm.cancel()
        // CRITICAL: isCancelling must flip immediately, BEFORE the dispatcher
        // runs any pending continuations. `runCurrent` would advance the
        // dispatcher; we deliberately don't call it here.
        assertTrue("isCancelling must flip synchronously on cancel()", vm.ui.value.isCancelling)
        assertTrue("isGenerating still true until coroutine unwinds", vm.ui.value.isGenerating)

        // Now let the cancellation propagate.
        advanceUntilIdle()
        assertFalse("isCancelling cleared after unwind", vm.ui.value.isCancelling)
        assertFalse("isGenerating cleared after unwind", vm.ui.value.isGenerating)
        assertEquals("partialText wiped on cancel", "", vm.ui.value.partialText)
    }

    @Test
    fun `cancel is a no-op when nothing is generating`() = runTest {
        val vm = newViewModel()
        // No send() ever issued, so currentJob is null.
        vm.cancel()
        runCurrent()
        // The cancel must not surface a phantom "Cancelling…" state.
        assertFalse(vm.ui.value.isCancelling)
        assertFalse(vm.ui.value.isGenerating)
    }

    @Test
    fun `repeated cancel taps are idempotent`() = runTest {
        val neverComplete = CompletableDeferred<Unit>()
        val mockLoop: AgentLoop = mockk()
        every { mockLoop.run(any<AgentTurnInput>()) } returns flow {
            emit(AgentEvent.TokenChunk("hi"))
            neverComplete.await()
        }
        every { agentLoopFactory.create(any(), any(), any()) } returns mockLoop

        val vm = newViewModel()
        vm.send("anything")
        advanceUntilIdle()

        vm.cancel()
        // Second tap on the (now disabled) button — verify we don't crash and
        // the state remains stable. In production the button is disabled by
        // isCancelling, but a queued tap from the prior frame could still fire.
        vm.cancel()
        vm.cancel()
        assertTrue(vm.ui.value.isCancelling)

        advanceUntilIdle()
        assertFalse(vm.ui.value.isCancelling)
    }

    private fun newViewModel(): ChatViewModel = ChatViewModel(
        agentLoopFactory = agentLoopFactory,
        sessionController = sessionController,
        languagePreferences = languagePreferences,
        translationIntentDetector = translationIntentDetector,
        memoryExtractor = memoryExtractor,
        memoryStore = memoryStore,
        conversationRepository = conversationRepository,
        telemetryCounters = NoOpTelemetryCounters,
        ttsPreferences = mockk(relaxed = true),
        speaker = mockk(relaxed = true),
        logger = ChatLogger { },
        thermalStatusProvider = thermalProvider,
    )
}
