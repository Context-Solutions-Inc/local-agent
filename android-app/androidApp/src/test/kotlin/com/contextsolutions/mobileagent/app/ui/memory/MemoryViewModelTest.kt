package com.contextsolutions.mobileagent.app.ui.memory

import com.contextsolutions.mobileagent.memory.BackupReader
import com.contextsolutions.mobileagent.memory.BackupWriter
import com.contextsolutions.mobileagent.memory.Memory
import com.contextsolutions.mobileagent.memory.MemoryBackupController
import com.contextsolutions.mobileagent.memory.MemoryBackupOps
import com.contextsolutions.mobileagent.memory.MemoryCategory
import com.contextsolutions.mobileagent.memory.MemoryHit
import com.contextsolutions.mobileagent.memory.MemoryPreferences
import com.contextsolutions.mobileagent.memory.MemoryStore
import com.contextsolutions.mobileagent.platform.AgentClock
import com.contextsolutions.mobileagent.ui.memory.BackupEvent
import com.contextsolutions.mobileagent.ui.memory.MemoryViewModel
import app.cash.turbine.test
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        // ViewModel uses Dispatchers.IO inside withContext blocks; route
        // them through the unconfined test dispatcher so runTest's
        // virtual scheduler can advance to completion.
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -- Initial load ----------------------------------------------------

    private fun buildViewModel(store: MemoryStore, prefs: MemoryPreferences = StubPrefs()): MemoryViewModel =
        MemoryViewModel(store, prefs, AgentClock(), NoOpBackupOps, testDispatcher)

    private object NoOpBackupOps : MemoryBackupOps {
        override suspend fun export(destination: BackupWriter): MemoryBackupController.ExportResult =
            error("export should not be called in this test")
        override suspend fun import(source: BackupReader): MemoryBackupController.ImportResult =
            error("import should not be called in this test")
    }

    /** Captures the writer/reader handed to the controller; returns canned results. */
    private class RecordingBackupOps(
        private val exportResult: MemoryBackupController.ExportResult = MemoryBackupController.ExportResult(memoryCount = 0),
        private val importResult: MemoryBackupController.ImportResult = MemoryBackupController.ImportResult(importedCount = 0, skippedCount = 0, replacedCount = 0),
        private val exportThrows: MemoryBackupController.BackupException? = null,
        private val importThrows: MemoryBackupController.BackupException? = null,
        private val importCapThrows: MemoryBackupController.ImportCapExceededException? = null,
    ) : MemoryBackupOps {
        val exportedTo = mutableListOf<BackupWriter>()
        val importedFrom = mutableListOf<BackupReader>()
        override suspend fun export(destination: BackupWriter): MemoryBackupController.ExportResult {
            exportedTo += destination
            exportThrows?.let { throw it }
            return exportResult
        }
        override suspend fun import(source: BackupReader): MemoryBackupController.ImportResult {
            importedFrom += source
            importCapThrows?.let { throw it }
            importThrows?.let { throw it }
            return importResult
        }
    }

    private fun buildViewModelWith(
        store: MemoryStore,
        ops: MemoryBackupOps,
        prefs: MemoryPreferences = StubPrefs(),
    ): MemoryViewModel = MemoryViewModel(store, prefs, AgentClock(), ops, testDispatcher)

    @Test
    fun refresh_loads_grouped_memories_and_creation_flag() = runTest {
        val store = InMemoryStore(
            initial = listOf(
                stub("a", MemoryCategory.PREFERENCE),
                stub("b", MemoryCategory.PREFERENCE),
                stub("c", MemoryCategory.PROFESSIONAL),
            ),
        )
        val vm = buildViewModel(store, StubPrefs(initialEnabled = true))
        vm.refresh()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(!state.isLoading)
        assertEquals(3, state.totalCount)
        assertEquals(2, state.memoriesByCategory[MemoryCategory.PREFERENCE]?.size)
        assertEquals(1, state.memoriesByCategory[MemoryCategory.PROFESSIONAL]?.size)
        assertEquals(0, state.memoriesByCategory[MemoryCategory.RELATIONSHIP]?.size ?: 0)
        assertTrue(state.creationEnabled)
    }

    @Test
    fun refresh_reflects_disabled_creation_pref() = runTest {
        val vm = buildViewModel(InMemoryStore(), StubPrefs(initialEnabled = false))
        vm.refresh()
        advanceUntilIdle()
        assertTrue(!vm.state.value.creationEnabled)
    }

    // -- Mutations -------------------------------------------------------

    @Test
    fun onDelete_removes_the_row_and_refreshes_state() = runTest {
        val store = InMemoryStore(
            initial = listOf(
                stub("a", MemoryCategory.PREFERENCE),
                stub("b", MemoryCategory.PREFERENCE),
            ),
        )
        val vm = buildViewModel(store)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(2, vm.state.value.totalCount)

        vm.onDelete("a")
        advanceUntilIdle()

        assertEquals(1, vm.state.value.totalCount)
        assertEquals(
            listOf("b"),
            vm.state.value.memoriesByCategory[MemoryCategory.PREFERENCE]?.map { it.id },
        )
    }

    @Test
    fun onClearAll_empties_the_store_and_state() = runTest {
        val store = InMemoryStore(
            initial = listOf(
                stub("a", MemoryCategory.PREFERENCE),
                stub("b", MemoryCategory.RELATIONSHIP),
            ),
        )
        val vm = buildViewModel(store)
        vm.refresh()
        advanceUntilIdle()

        vm.onClearAll()
        advanceUntilIdle()

        assertEquals(0, vm.state.value.totalCount)
        assertTrue(vm.state.value.memoriesByCategory.values.all { it.isEmpty() })
    }

    @Test
    fun onToggleCreation_persists_and_updates_state() = runTest {
        val prefs = StubPrefs(initialEnabled = true)
        val vm = buildViewModel(InMemoryStore(), prefs)
        vm.refresh()
        advanceUntilIdle()

        vm.onToggleCreation(false)
        assertTrue("preference must persist", !prefs.creationEnabled())
        assertTrue("state must reflect the toggle", !vm.state.value.creationEnabled)

        vm.onToggleCreation(true)
        assertTrue(prefs.creationEnabled())
        assertTrue(vm.state.value.creationEnabled)
    }

    // -- Per-conversation slice ------------------------------------------

    @Test
    fun observeConversation_emits_only_matching_rows() = runTest {
        val store = InMemoryStore(
            initial = listOf(
                stub("a", MemoryCategory.PREFERENCE, conversationId = "conv-1"),
                stub("b", MemoryCategory.PREFERENCE, conversationId = "conv-2"),
                stub("c", MemoryCategory.RELATIONSHIP, conversationId = "conv-1"),
            ),
        )
        val vm = buildViewModel(store)
        vm.observeConversation("conv-1")
        advanceUntilIdle()

        assertEquals(setOf("a", "c"), vm.conversationMemories.value.map { it.id }.toSet())
    }

    @Test
    fun delete_propagates_to_per_conversation_slice() = runTest {
        val store = InMemoryStore(
            initial = listOf(
                stub("a", MemoryCategory.PREFERENCE, conversationId = "conv-1"),
                stub("b", MemoryCategory.PREFERENCE, conversationId = "conv-1"),
            ),
        )
        val vm = buildViewModel(store)
        vm.observeConversation("conv-1")
        advanceUntilIdle()

        vm.onDelete("a")
        advanceUntilIdle()

        assertEquals(listOf("b"), vm.conversationMemories.value.map { it.id })
    }

    @Test
    fun stopObservingConversation_clears_the_slice() = runTest {
        val store = InMemoryStore(
            initial = listOf(stub("a", MemoryCategory.PREFERENCE, conversationId = "conv-1")),
        )
        val vm = buildViewModel(store)
        vm.observeConversation("conv-1")
        advanceUntilIdle()
        assertTrue(vm.conversationMemories.value.isNotEmpty())

        vm.stopObservingConversation()
        assertTrue(vm.conversationMemories.value.isEmpty())
    }

    // -- Test fixtures ---------------------------------------------------

    private class StubPrefs(initialEnabled: Boolean = true) : MemoryPreferences {
        private var enabled = initialEnabled
        override fun creationEnabled(): Boolean = enabled
        override fun setCreationEnabled(enabled: Boolean) { this.enabled = enabled }
    }

    /**
     * Minimal in-memory store. The full SqlDelight one is exercised by
     * SqlDelightMemoryStoreTest; here we just need a list-shaped backing
     * for the ViewModel's listAll / deleteById / deleteAll /
     * listForConversation surface.
     */
    private class InMemoryStore(initial: List<Memory> = emptyList()) : MemoryStore {
        private val rows: MutableList<Memory> = initial.toMutableList()

        override suspend fun insert(memory: Memory) { rows += memory }
        override suspend fun deleteById(id: String) { rows.removeAll { it.id == id } }
        override suspend fun deleteByCosine(embedding: FloatArray, threshold: Double, now: Long): Memory? = null
        override suspend fun retrieveTopK(
            queryEmbedding: FloatArray,
            k: Int,
            threshold: Double,
            now: Long,
        ): List<MemoryHit> = emptyList()
        override suspend fun findCosineMatch(embedding: FloatArray, threshold: Double, now: Long): Memory? = null
        override suspend fun count(now: Long): Int = rows.size
        override suspend fun listForConversation(conversationId: String): List<Memory> =
            rows.filter { it.conversationId == conversationId }
        override suspend fun countForConversation(conversationId: String): Int =
            rows.count { it.conversationId == conversationId }
        override suspend fun listAll(): List<Memory> = rows.toList()
        override suspend fun deleteAll() { rows.clear() }
    }

    // -- Backup wiring ----------------------------------------------------

    @Test
    fun onExport_routes_through_backup_controller_and_emits_event() = runTest {
        val ops = RecordingBackupOps(
            exportResult = MemoryBackupController.ExportResult(memoryCount = 3),
        )
        val vm = buildViewModelWith(InMemoryStore(), ops)
        val writer = mockk<BackupWriter>(relaxed = true)

        vm.backupEvents.test {
            vm.onExport(writer)
            val event = awaitItem()
            assertTrue(event is BackupEvent.Exported)
            assertEquals(3, (event as BackupEvent.Exported).count)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(writer), ops.exportedTo)
        assertTrue(!vm.isBackupBusy.value)
    }

    @Test
    fun onImport_refreshes_state_and_emits_imported_event() = runTest {
        val store = InMemoryStore(initial = listOf(stub("old", MemoryCategory.PREFERENCE)))
        val ops = RecordingBackupOps(
            importResult = MemoryBackupController.ImportResult(importedCount = 5, skippedCount = 0, replacedCount = 1),
        )
        val vm = buildViewModelWith(store, ops)
        vm.refresh()
        advanceUntilIdle()

        vm.backupEvents.test {
            vm.onImport(mockk<BackupReader>(relaxed = true))
            val event = awaitItem()
            assertTrue(event is BackupEvent.Imported)
            assertEquals(5, (event as BackupEvent.Imported).imported)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(!vm.isBackupBusy.value)
    }

    @Test
    fun onImport_over_cap_shows_dialog_without_error_event() = runTest {
        val ops = RecordingBackupOps(
            importCapThrows = MemoryBackupController.ImportCapExceededException(limit = 3, found = 5),
        )
        val vm = buildViewModelWith(InMemoryStore(), ops)

        vm.onImport(mockk<BackupReader>(relaxed = true))
        advanceUntilIdle()

        val info = vm.importCapExceeded.value
        assertEquals(3, info?.limit)
        assertEquals(5, info?.found)
        assertTrue(!vm.isBackupBusy.value)

        vm.dismissImportCapDialog()
        assertEquals(null, vm.importCapExceeded.value)
    }

    @Test
    fun onExport_failure_emits_error_event_and_clears_busy() = runTest {
        val ops = RecordingBackupOps(
            exportThrows = MemoryBackupController.BackupException("Disk full"),
        )
        val vm = buildViewModelWith(InMemoryStore(), ops)

        vm.backupEvents.test {
            vm.onExport(mockk<BackupWriter>(relaxed = true))
            val event = awaitItem()
            assertTrue(event is BackupEvent.Error)
            assertEquals("Disk full", (event as BackupEvent.Error).message)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(!vm.isBackupBusy.value)
    }

    private fun stub(
        id: String,
        category: MemoryCategory,
        conversationId: String? = null,
    ): Memory = Memory(
        id = id,
        text = "memory $id",
        category = category,
        conversationId = conversationId,
        createdAtEpochMs = 0L,
        lastAccessedEpochMs = 0L,
        accessCount = 0,
        embedding = FloatArray(Memory.EMBEDDING_DIM) { 0f },
        expiresAtEpochMs = null,
    )
}
