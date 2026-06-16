package com.contextsolutions.localagent.sync

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable

/**
 * Wire model for mobile↔desktop sync (PR #57). Records carry stable UUIDs +
 * `updatedAt` timestamps so the receiver merges last-write-wins per record, and
 * a nullable `deletedAt` tombstone so deletes propagate. Embeddings are NEVER on
 * the wire — the receiver re-embeds memory text on-device (mirrors
 * `MemoryBackupController`, tolerant of embedder version drift).
 */
@Serializable
data class SyncBundle(
    /** Highest `updatedAt` covered by this bundle; the receiver advances its watermark to it. */
    val maxWatermarkMs: Long = 0,
    val conversations: List<ConversationSyncRecord> = emptyList(),
    val memories: List<MemorySyncRecord> = emptyList(),
    val settings: List<SettingSyncRecord> = emptyList(),
    val jobs: List<JobSyncRecord> = emptyList(),
) {
    val isEmpty: Boolean
        get() = conversations.isEmpty() && memories.isEmpty() && settings.isEmpty() && jobs.isEmpty()
}

@Serializable
data class ConversationSyncRecord(
    val id: String,
    val title: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val truncationAcknowledgedAt: Long? = null,
    val deletedAtEpochMs: Long? = null,
    val messages: List<MessageSyncRecord> = emptyList(),
)

@Serializable
data class MessageSyncRecord(
    val id: String,
    val role: String,
    val content: String,
    val toolCallJson: String? = null,
    val toolResultJson: String? = null,
    val createdAtEpochMs: Long,
    val sequenceIndex: Long,
    /** Base64 of the display-only JPEG (PR #49); null when absent. */
    val imageBytesBase64: String? = null,
    val renderMarkdown: Boolean = true,
)

@Serializable
data class MemorySyncRecord(
    val id: String,
    val text: String,
    val category: String,
    val conversationId: String? = null,
    val createdAtEpochMs: Long,
    val lastAccessedEpochMs: Long,
    val accessCount: Int = 0,
    val expiresAtEpochMs: Long? = null,
    val updatedAtEpochMs: Long,
    val deletedAtEpochMs: Long? = null,
    // NOTE: no embedding — the receiver regenerates it from [text] on-device.
)

/**
 * A synced job (PR #70). The desktop pushes the full definition + denormalized
 * last-run state (incl. [lastRunConversationId], so mobile can open the run
 * conversation — which itself syncs via [ConversationSyncRecord]). The only
 * mutation a mobile peer may push is a [paused] toggle; the desktop's
 * [JobSyncPolicy] enforces that on apply. Commands/prompts are NEVER accepted
 * from a peer — the §2 trust boundary.
 */
@Serializable
data class JobSyncRecord(
    val id: String,
    val name: String,
    val command: String,
    val prompt: String,
    val workingDir: String? = null,
    val scheduleType: String,
    val cronExpression: String? = null,
    val fireAtEpochMs: Long? = null,
    val paused: Boolean = false,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val deletedAtEpochMs: Long? = null,
    val lastRunStatus: String? = null,
    val lastRunAtEpochMs: Long? = null,
    val lastRunSummary: String? = null,
    val lastRunConversationId: String? = null,
)

/** A synced preference (allow-list only — never secrets; see [SyncedSettingsKeys]). */
@Serializable
data class SettingSyncRecord(
    val key: String,
    val value: String,
    val updatedAtEpochMs: Long,
)

/** The safe allow-list of preference keys that sync between devices (PR #57). */
object SyncedSettingsKeys {
    const val LANGUAGE = "language"
    const val TTS_ENABLED = "tts_enabled"
    const val THEME = "theme"

    val ALL = setOf(LANGUAGE, TTS_ENABLED, THEME)
}

/**
 * The seam the desktop link server's sync routes and the mobile
 * [SyncController] both drive. One implementation per app reads/writes the local
 * SQLDelight stores + preference allow-list.
 */
interface LinkSyncService {
    /** All local records changed strictly after [sinceMs] (includes tombstones). */
    suspend fun changesSince(sinceMs: Long): SyncBundle

    /** Merge a peer bundle into the local stores last-write-wins (re-embeds memories). */
    suspend fun applyFromPeer(bundle: SyncBundle)

    /** Emits whenever a LOCAL write happens, so a subscriber can pull + push. */
    val localChanges: SharedFlow<Unit>
}
