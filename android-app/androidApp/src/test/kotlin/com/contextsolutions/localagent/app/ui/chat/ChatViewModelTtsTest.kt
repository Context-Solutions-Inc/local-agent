package com.contextsolutions.localagent.ui.chat

import com.contextsolutions.localagent.agent.AgentEvent
import com.contextsolutions.localagent.agent.AgentLoop
import com.contextsolutions.localagent.agent.AgentTurnInput
import com.contextsolutions.localagent.voice.ChatSpeaker
import com.contextsolutions.localagent.voice.TtsPreferences
import com.contextsolutions.localagent.agent.ChatMessage
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contract for the read-aloud (text-to-speech) path:
 *  - a finished answer is spoken only when the toggle is ON;
 *  - the spoken text is markdown-stripped (no raw `**`/`$`);
 *  - turning the toggle off stops in-progress speech.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTtsTest {

    private val testDispatcher = StandardTestDispatcher()

    private val agentLoopFactory: AgentLoopFactory = mockk()
    private val sessionController: ChatSessionController = mockk()
    private val languagePreferences: LanguagePreferences = mockk(relaxed = true)
    private val memoryExtractor: MemoryExtractor = mockk(relaxed = true)
    private val memoryStore: MemoryStore = mockk(relaxed = true)
    private val conversationRepository: ConversationRepository = mockk(relaxed = true)
    private val thermalProvider: ThermalStatusProvider = mockk(relaxed = true)
    private val translationIntentDetector =
        mockk<com.contextsolutions.localagent.agent.TranslationIntentDetector>(relaxed = true)

    private val sessionStateFlow = MutableStateFlow<SessionState>(SessionState.Loaded(Accelerator.GPU))

    /** In-memory toggle so the ViewModel reads/writes a real value. */
    private val ttsPrefs = object : TtsPreferences {
        private val state = MutableStateFlow(false)
        override fun isEnabled() = state.value
        override fun enabledFlow() = state
        override fun setEnabled(enabled: Boolean) { state.value = enabled }
    }

    /** Records what was spoken / whether stop fired. */
    private val speaker = object : ChatSpeaker {
        val all = mutableListOf<String>()
        val spoken: String? get() = all.lastOrNull()
        var stopCount = 0
        private val speaking = MutableStateFlow(false)
        override val isSpeaking = speaking
        override fun speak(text: String) { all += text }
        override fun stop() { stopCount++ }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { sessionController.state } returns sessionStateFlow
        coEvery { sessionController.newSession() } returns mockk(relaxed = true)
        every { thermalProvider.statusFlow() } returns flowOf(ThermalStatus.NONE)
        every { thermalProvider.current() } returns ThermalStatus.NONE
        every { translationIntentDetector.isTranslationRequest(any(), any()) } returns false
        coEvery { conversationRepository.appendMessage(any(), any(), any()) } returns 0L
        wireLoop("**Hello** there")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun wireLoop(answer: String) {
        val msg = ChatMessage.Assistant(text = answer, citations = emptyList(), renderMarkdown = true)
        val loop: AgentLoop = mockk()
        every { loop.run(any<AgentTurnInput>()) } returns kotlinx.coroutines.flow.flow {
            emit(AgentEvent.GenerationStarted) // LLM path — fires the working ack
            emit(AgentEvent.Done(message = msg, turnMessages = listOf(ChatMessage.User("hi"), msg), skipMemoryExtraction = true))
        }
        every { agentLoopFactory.create(any(), any(), any()) } returns loop
    }

    @Test
    fun `does not speak when toggle is off`() = runTest {
        val vm = newViewModel()
        vm.send("hi")
        advanceUntilIdle()
        assertNull("must stay silent while read-aloud is off", speaker.spoken)
    }

    @Test
    fun `speaks markdown-stripped answer when toggle is on`() = runTest {
        val vm = newViewModel()
        vm.toggleTts() // on
        vm.send("hi")
        advanceUntilIdle()
        assertEquals("Hello there", speaker.spoken)
    }

    @Test
    fun `toggling off stops in-progress speech`() = runTest {
        val vm = newViewModel()
        vm.toggleTts() // on
        val before = speaker.stopCount
        vm.toggleTts() // off
        assertTrue("turning off must stop speech", speaker.stopCount > before)
    }

    @Test
    fun `speaks a working ack before the answer when toggle is on`() = runTest {
        val vm = newViewModel()
        vm.toggleTts() // on
        vm.send("hi")
        advanceUntilIdle()
        // First utterance is a "working on it" ack; the answer comes after.
        assertTrue("expected a spoken ack at send time", speaker.all.size >= 2)
        assertTrue(
            "first utterance should be a working-ack phrase",
            speaker.all.first() in AckPhrasePicker.DEFAULT,
        )
        assertEquals("Hello there", speaker.all.last())
    }

    @Test
    fun `deterministic turn reads the answer but skips the working ack`() = runTest {
        // A deterministic render (weather/finance card) emits Done with NO
        // GenerationStarted, so the ack must be suppressed — only the card is read.
        val card = ChatMessage.Assistant(text = "72F and sunny", citations = emptyList(), renderMarkdown = false)
        val loop: AgentLoop = mockk()
        every { loop.run(any<AgentTurnInput>()) } returns kotlinx.coroutines.flow.flow {
            emit(AgentEvent.Done(message = card, turnMessages = listOf(ChatMessage.User("weather"), card), skipMemoryExtraction = true))
        }
        every { agentLoopFactory.create(any(), any(), any()) } returns loop

        val vm = newViewModel()
        vm.toggleTts() // on
        vm.send("weather in miami")
        advanceUntilIdle()
        assertEquals(listOf("72F and sunny"), speaker.all)
    }

    @Test
    fun `speaks periodic still-working cues every 5s during a long stream`() = runTest {
        // GenerationStarted, then a 12s gap before Done — the ticker should fire
        // at 5s and 10s (two cues), then Done stops it and reads the answer.
        val msg = ChatMessage.Assistant(text = "Final answer", citations = emptyList(), renderMarkdown = true)
        val loop: AgentLoop = mockk()
        every { loop.run(any<AgentTurnInput>()) } returns kotlinx.coroutines.flow.flow {
            emit(AgentEvent.GenerationStarted)
            kotlinx.coroutines.delay(12_000)
            emit(AgentEvent.Done(message = msg, turnMessages = listOf(ChatMessage.User("hi"), msg), skipMemoryExtraction = true))
        }
        every { agentLoopFactory.create(any(), any(), any()) } returns loop

        val vm = newViewModel()
        vm.toggleTts() // on
        vm.send("write me an essay")
        advanceUntilIdle()

        val stillWorking = speaker.all.count { it in AckPhrasePicker.STILL_WORKING }
        assertEquals("expected two 5s heartbeat cues during the 12s stream", 2, stillWorking)
        assertEquals("answer is read once streaming ends", "Final answer", speaker.all.last())
    }

    @Test
    fun `no ack is spoken when toggle is off`() = runTest {
        val vm = newViewModel()
        vm.send("hi")
        advanceUntilIdle()
        assertTrue("must stay silent when read-aloud is off", speaker.all.isEmpty())
    }

    @Test
    fun `mic toggle defaults off and is session-controlled`() = runTest {
        val vm = newViewModel()
        assertEquals(false, vm.micEnabled.value)
        vm.setMicEnabled(true)
        assertEquals(true, vm.micEnabled.value)
        vm.setMicEnabled(false)
        assertEquals(false, vm.micEnabled.value)
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
        ttsPreferences = ttsPrefs,
        speaker = speaker,
        logger = ChatLogger { },
        thermalStatusProvider = thermalProvider,
    )
}
