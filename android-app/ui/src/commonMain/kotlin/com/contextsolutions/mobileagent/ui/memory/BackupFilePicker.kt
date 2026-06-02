package com.contextsolutions.mobileagent.ui.memory

import androidx.compose.runtime.Composable
import com.contextsolutions.mobileagent.memory.BackupReader
import com.contextsolutions.mobileagent.memory.BackupWriter

/**
 * Platform file-picker for memory backup export/import (docs/DESKTOP_PORT_PLAN.md
 * Phase 9). Android registers SAF `CreateDocument`/`OpenDocument` launchers in
 * composition (so the `expect` is a `@Composable`); desktop shows a Swing
 * chooser. The picked file is handed back as a platform-neutral
 * [BackupWriter]/[BackupReader] (`null` if the user cancelled) that
 * `MemoryBackupController` consumes.
 */
interface BackupFilePicker {
    fun launchExport(suggestedName: String, onPicked: (BackupWriter?) -> Unit)
    fun launchImport(onPicked: (BackupReader?) -> Unit)
}

@Composable
expect fun rememberBackupFilePicker(): BackupFilePicker
