package com.contextsolutions.localagent.memory

/**
 * Platform-neutral handles for the memory backup file the user picks
 * (docs/DESKTOP_PORT_PLAN.md Phase 9). The `:ui` file-picker produces these;
 * [MemoryBackupController] consumes them — keeping the controller free of
 * `android.net.Uri` / `java.io.File` so it lives in shared commonMain.
 *
 * On Android these wrap a SAF `Uri` + `contentResolver`; on desktop a
 * `java.io.File`. UTF-8 text either way (the backup is JSON).
 */
interface BackupWriter {
    suspend fun writeText(text: String)
}

interface BackupReader {
    suspend fun readText(): String
}
