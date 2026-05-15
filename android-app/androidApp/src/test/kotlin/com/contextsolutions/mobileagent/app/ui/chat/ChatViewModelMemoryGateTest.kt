package com.contextsolutions.mobileagent.app.ui.chat

import com.contextsolutions.mobileagent.agent.AgentEvent
import com.contextsolutions.mobileagent.agent.AgentLoop
import com.contextsolutions.mobileagent.agent.AgentTurnInput
import com.contextsolutions.mobileagent.agent.TranslationIntentDetector
import com.contextsolutions.mobileagent.app.di.AgentLoopFactory
import com.contextsolutions.mobileagent.app.service.InferenceSessionManager
import com.contextsolutions.mobileagent.app.service.ModelInventory
import com.contextsolutions.mobileagent.app.service.SessionState
import com.contextsolutions.mobileagent.conversation.ConversationRepository
import com.contextsolutions.mobileagent.inference.Accelerator
import com.contextsolutions.mobileagent.inference.MemoryHeadroomProvider
import com.contextsolutions.mobileagent.inference.ThermalStatus
import com.contextsolutions.mobileagent.inference.ThermalStatusProvider
import com.contextsolutions.mobileagent.language.LanguagePreferences
import com.contextsolutions.mobileagent.memory.MemoryExtractor
import com.contextsolutions.mobileagent.memory.MemoryStore
import com.contextsolutions.mobileagent.telemetry.NoOpTelemetryCounters
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * PR #16 — locks the contract on `ChatViewModel.send()`'s memory gate:
 *
 *  - When the session is NOT loaded and free RAM is below the cold-load
 *    threshold, the gate fires: `memoryWarning` is populated and
 *    `agentLoopFactory.create()` is never invoked.
 *  - When the session IS loaded, the gate is skipped regardless of memory
 *    state — the user isn't paying a cold load, and the proactive watchdog
 *    has the back-of-house covered.
 *  - `retryAfterMemoryWarning()` clears the warning and re-runs `send()`
 *    with the pending prompt, re-checking the gate against fresh readings.
 *  - `dismissMemoryWarning()` clears the warning without retrying.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelMemoryGateTest {

    private val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher()

    private val agentLoopFactory: AgentLoopFactory = mockk(relaxed = true)
    private val sessionManager: InferenceSessionManager = mockk()
    private val inventory: ModelInventory = mockk()
    private val languagePreferences: LanguagePreferences = mockk(relaxed = true)
    private val translationIntentDetector: TranslationIntentDetector = mockk(relaxed = true)
    private val memoryExtractor: MemoryExtractor = mockk(relaxed = true)
    private val memoryStore: MemoryStore = mockk(relaxed = true)
    private val conversationRepository: ConversationRepository = mockk(relaxed = true)
    private val thermalProvider: ThermalStatusProvider = mockk(relaxed = true)

    private val sessionStateFlow = MutableStateFlow<SessionState>(SessionState.Unloaded)
    private val provider = FakeMemoryHeadroomProvider()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { sessionManager.state } returns sessionStateFlow
        every { thermalProvider.statusFlow() } returns flowOf(ThermalStatus.NONE)
        every { thermalProvider.current() } returns ThermalStatus.NONE
        every { inventory.localFile() } returns File("/data/test/model.litertlm")
        every { translationIntentDetector.isTranslationRequest(any(), any()) } returns false
        coEvery { conversationRepository.appendMessage(any(), any(), any()) } returns 0L
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `cold-load gate fires when session is Unloaded and memory is below 2 GiB`() = runTest {
        sessionStateFlow.value = SessionState.Unloaded
        provider.value = 1_500L * 1024 * 1024 // 1.46 GiB — below the 2.0 GiB cold-load threshold

        val vm = newViewModel()
        vm.send("hello there")
        advanceUntilIdle()

        val warning = vm.memoryWarning.value
        assertNotNull("expected cold-load memory warning to fire", warning)
        assertEquals("hello there", warning!!.pendingPrompt)
        assertEquals("modelAlreadyLoaded must be false on cold-load refusal", false, warning.modelAlreadyLoaded)
        verify(exactly = 0) { agentLoopFactory.create(any(), any(), any()) }
    }

    @Test
    fun `hot-path gate fires when session is Loaded and memory is below 1 GiB`() = runTest {
        // The bug from on-device testing: under tight memory the watchdog
        // would yank the model mid-turn, leaving inference to produce a
        // blank reply. The hot-path gate refuses the send instead.
        sessionStateFlow.value = SessionState.Loaded(Accelerator.CPU)
        provider.value = 700L * 1024 * 1024 // 700 MB — below 1.0 GiB hot-path threshold

        val vm = newViewModel()
        vm.send("hello there")
        advanceUntilIdle()

        val warning = vm.memoryWarning.value
        assertNotNull("expected hot-path memory warning to fire", warning)
        assertEquals("modelAlreadyLoaded must be true on hot-path refusal", true, warning!!.modelAlreadyLoaded)
        verify(exactly = 0) { agentLoopFactory.create(any(), any(), any()) }
    }

    @Test
    fun `gate is bypassed when session is Loaded and memory is comfortably above hot-path threshold`() = runTest {
        sessionStateFlow.value = SessionState.Loaded(Accelerator.CPU)
        provider.value = 4_000L * 1024 * 1024 // 4 GiB — comfortable

        val mockLoop: AgentLoop = mockk()
        every { mockLoop.run(any<AgentTurnInput>()) } returns flowOf<AgentEvent>()
        every { agentLoopFactory.create(any(), any(), any()) } returns mockLoop

        val vm = newViewModel()
        vm.send("hello there")
        advanceUntilIdle()

        assertNull("gate must NOT fire on comfortable hot path", vm.memoryWarning.value)
        verify(atLeast = 1) { agentLoopFactory.create(any(), any(), any()) }
    }

    @Test
    fun `retry clears warning and re-runs send`() = runTest {
        sessionStateFlow.value = SessionState.Unloaded
        provider.value = 1_500L * 1024 * 1024 // Below cold-load threshold (2.0 GiB)

        val vm = newViewModel()
        vm.send("a question")
        advanceUntilIdle()
        assertNotNull(vm.memoryWarning.value)

        // User freed memory; Retry should succeed.
        provider.value = 4_000L * 1024 * 1024 // 4 GiB
        val mockLoop: AgentLoop = mockk()
        every { mockLoop.run(any<AgentTurnInput>()) } returns flowOf<AgentEvent>()
        every { agentLoopFactory.create(any(), any(), any()) } returns mockLoop

        vm.retryAfterMemoryWarning()
        advanceUntilIdle()

        assertNull("retry must clear the warning when memory is now sufficient", vm.memoryWarning.value)
        verify(atLeast = 1) { agentLoopFactory.create(any(), any(), any()) }
    }

    @Test
    fun `dismiss clears warning without retrying`() = runTest {
        sessionStateFlow.value = SessionState.Unloaded
        provider.value = 1_500L * 1024 * 1024

        val vm = newViewModel()
        vm.send("dropped prompt")
        advanceUntilIdle()
        assertNotNull(vm.memoryWarning.value)

        vm.dismissMemoryWarning()
        advanceUntilIdle()
        assertNull(vm.memoryWarning.value)
        verify(exactly = 0) { agentLoopFactory.create(any(), any(), any()) }
    }

    private fun newViewModel(): ChatViewModel = ChatViewModel(
        agentLoopFactory = agentLoopFactory,
        sessionManager = sessionManager,
        inventory = inventory,
        languagePreferences = languagePreferences,
        translationIntentDetector = translationIntentDetector,
        memoryExtractor = memoryExtractor,
        memoryStore = memoryStore,
        conversationRepository = conversationRepository,
        telemetryCounters = NoOpTelemetryCounters,
        memoryHeadroomProvider = provider,
        thermalStatusProvider = thermalProvider,
    )
}

private class FakeMemoryHeadroomProvider : MemoryHeadroomProvider {
    @Volatile var value: Long = Long.MAX_VALUE
    override fun availableBytes(): Long = value
}
