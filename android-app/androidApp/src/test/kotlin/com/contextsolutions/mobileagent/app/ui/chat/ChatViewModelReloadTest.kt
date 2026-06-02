package com.contextsolutions.mobileagent.ui.chat

import com.contextsolutions.mobileagent.agent.ChatMessage
import com.contextsolutions.mobileagent.di.AgentLoopFactory
import com.contextsolutions.mobileagent.agent.ChatLogger
import com.contextsolutions.mobileagent.agent.ChatSessionController
import com.contextsolutions.mobileagent.inference.SessionState
import com.contextsolutions.mobileagent.conversation.ConversationRecord
import com.contextsolutions.mobileagent.conversation.ConversationRepository
import com.contextsolutions.mobileagent.inference.Accelerator
import com.contextsolutions.mobileagent.inference.ThermalStatus
import com.contextsolutions.mobileagent.inference.ThermalStatusProvider
import com.contextsolutions.mobileagent.language.LanguagePreferences
import com.contextsolutions.mobileagent.memory.MemoryExtractor
import com.contextsolutions.mobileagent.memory.MemoryStore
import com.contextsolutions.mobileagent.telemetry.NoOpTelemetryCounters
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * PR #49 — resuming a conversation must re-surface a persisted photo. Asserts
 * [ChatViewModel.loadConversation] maps a loaded image-bearing
 * [ChatMessage.User] into a [UiMessage.User] carrying the JPEG bytes (which
 * [UserBubble] decodes on demand), and that a text-only turn stays imageless.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelReloadTest {

    private val testDispatcher = StandardTestDispatcher()

    private val agentLoopFactory: AgentLoopFactory = mockk()
    private val sessionController: ChatSessionController = mockk()
    private val languagePreferences: LanguagePreferences = mockk(relaxed = true)
    private val translationIntentDetector: com.contextsolutions.mobileagent.agent.TranslationIntentDetector =
        mockk(relaxed = true)
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
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadConversation surfaces persisted image bytes on the user bubble`() = runTest {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x42, 0x13)
        coEvery { conversationRepository.get("c1") } returns ConversationRecord(
            id = "c1",
            title = "photo chat",
            createdAtEpochMs = 100L,
            updatedAtEpochMs = 200L,
            truncationAcknowledgedAtEpochMs = null,
        )
        coEvery { conversationRepository.loadMessages("c1") } returns listOf(
            ChatMessage.User("look at this", imageBytes = jpeg),
            ChatMessage.Assistant("that's a cat"),
            ChatMessage.User("thanks"),
        )

        val vm = newViewModel()
        vm.loadConversation("c1")
        advanceUntilIdle()

        val users = vm.ui.value.messages.filterIsInstance<UiMessage.User>()
        assertEquals(2, users.size)
        assertNotNull("resumed image turn must carry bytes for re-render", users[0].imageBytes)
        assertArrayEquals(jpeg, users[0].imageBytes)
        assertNull("text-only turn stays imageless", users[1].imageBytes)
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
