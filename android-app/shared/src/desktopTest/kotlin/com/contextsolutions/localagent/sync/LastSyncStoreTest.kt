package com.contextsolutions.localagent.sync

import com.contextsolutions.localagent.platform.DesktopJsonStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [DesktopLastSyncStore] returns null before the first sync and persists the
 * wall-clock instant across instances (PR #70) — so the Jobs screen's "Synced Nm
 * ago" survives a restart and shows "Never synced" until the first reconcile.
 */
class LastSyncStoreTest {

    private fun tempFile(): File =
        Files.createTempFile("sync_state", ".json").toFile().also { it.deleteOnExit() }

    @Test
    fun nullBeforeFirstSync() {
        val store = DesktopLastSyncStore(DesktopJsonStore(tempFile()))
        assertNull(store.get())
    }

    @Test
    fun persistsAcrossInstances() {
        val file = tempFile()
        DesktopLastSyncStore(DesktopJsonStore(file)).set(1_700_000_000_000L)
        // A fresh store over the same file (a restart) reads the persisted value.
        assertEquals(1_700_000_000_000L, DesktopLastSyncStore(DesktopJsonStore(file)).get())
    }
}
