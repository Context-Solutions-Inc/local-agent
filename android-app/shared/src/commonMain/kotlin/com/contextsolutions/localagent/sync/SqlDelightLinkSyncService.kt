package com.contextsolutions.localagent.sync

import com.contextsolutions.localagent.db.ConversationsQueries
import com.contextsolutions.localagent.db.JobsQueries
import com.contextsolutions.localagent.db.MemoriesQueries
import com.contextsolutions.localagent.memory.EmbedderEngine
import com.contextsolutions.localagent.memory.Memory
import com.contextsolutions.localagent.memory.internal.EmbeddingBlob
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext

/**
 * SQLDelight-backed [LinkSyncService] (PR #57) — used identically on both
 * platforms (it talks to the shared `:shared` stores). Reads the change feed
 * (`*ChangedSince`, which includes tombstones) and applies a peer bundle through
 * the last-write-wins `upsert*FromPeer` queries. Writes go through the RAW
 * queries (not the repos) so [LocalChangeBus] never re-fires on a remote-applied
 * change — no echo loop.
 *
 * **Embeddings are never on the wire.** A received memory carries only text +
 * metadata; this service re-embeds the text on-device before insert (mirrors
 * `MemoryBackupController`'s import). A memory whose embedding can't be computed
 * (embedder not yet warm / failed) is skipped — it'll arrive on the next sync.
 *
 * **Scope (v1).** Conversations + messages + memories sync. Settings sync is a
 * forward-compatible no-op (the wire model carries [SettingSyncRecord], but no
 * keys are emitted/applied yet) — a deliberate follow-up.
 */
@OptIn(ExperimentalEncodingApi::class)
class SqlDelightLinkSyncService(
    private val conversations: ConversationsQueries,
    private val memories: MemoriesQueries,
    private val jobs: JobsQueries,
    private val jobPolicy: JobSyncPolicy,
    private val embedder: EmbedderEngine,
    private val bus: LocalChangeBus,
    private val logger: (String) -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Desktop-only hook fired AFTER a peer's paused toggle is applied to an
     * existing job (the raw `*FromPeer` write doesn't go through the repo, so it
     * never fires [LocalChangeBus]). The desktop binds this to its scheduler so a
     * mobile pause actually cancels/rearms the job's coroutine. No-op on mobile.
     */
    private val onJobPausedFromPeer: (id: String, paused: Boolean) -> Unit = { _, _ -> },
) : LinkSyncService {

    override val localChanges: SharedFlow<Unit> = bus.changes

    override suspend fun changesSince(sinceMs: Long): SyncBundle = withContext(ioDispatcher) {
        var watermark = sinceMs

        val convRecords = conversations.selectConversationsChangedSince(sinceMs).executeAsList().map { c ->
            watermark = maxOf(watermark, c.updated_at_epoch_ms)
            val msgs = conversations.selectMessagesByConversation(c.id).executeAsList().map { m ->
                MessageSyncRecord(
                    id = m.id,
                    role = m.role,
                    content = m.content,
                    toolCallJson = m.tool_call_json,
                    toolResultJson = m.tool_result_json,
                    createdAtEpochMs = m.created_at_epoch_ms,
                    sequenceIndex = m.sequence_index,
                    imageBytesBase64 = m.image_bytes?.let { Base64.Default.encode(it) },
                    renderMarkdown = m.render_markdown != 0L,
                )
            }
            ConversationSyncRecord(
                id = c.id,
                title = c.title,
                createdAtEpochMs = c.created_at_epoch_ms,
                updatedAtEpochMs = c.updated_at_epoch_ms,
                truncationAcknowledgedAt = c.truncation_acknowledged_at,
                deletedAtEpochMs = c.deleted_at_epoch_ms,
                messages = msgs,
            )
        }

        val memRecords = memories.selectMemoriesChangedSince(sinceMs).executeAsList().map { mem ->
            val updated = mem.updated_at_epoch_ms ?: mem.created_at_epoch_ms
            watermark = maxOf(watermark, updated)
            MemorySyncRecord(
                id = mem.id,
                text = mem.text,
                category = mem.category,
                conversationId = mem.conversation_id,
                createdAtEpochMs = mem.created_at_epoch_ms,
                lastAccessedEpochMs = mem.last_accessed_epoch_ms,
                accessCount = mem.access_count.toInt(),
                expiresAtEpochMs = mem.expires_at_epoch_ms,
                updatedAtEpochMs = updated,
                deletedAtEpochMs = mem.deleted_at_epoch_ms,
            )
        }

        val jobRecords = jobPolicy.outgoing(
            jobs.selectJobsChangedSince(sinceMs).executeAsList().map { j ->
                watermark = maxOf(watermark, j.updated_at_epoch_ms)
                JobSyncRecord(
                    id = j.id,
                    name = j.name,
                    command = j.command,
                    prompt = j.prompt,
                    workingDir = j.working_dir,
                    scheduleType = j.schedule_type,
                    cronExpression = j.cron_expression,
                    fireAtEpochMs = j.fire_at_epoch_ms,
                    paused = j.paused != 0L,
                    createdAtEpochMs = j.created_at_epoch_ms,
                    updatedAtEpochMs = j.updated_at_epoch_ms,
                    deletedAtEpochMs = j.deleted_at_epoch_ms,
                    lastRunStatus = j.last_run_status,
                    lastRunAtEpochMs = j.last_run_at_epoch_ms,
                    lastRunSummary = j.last_run_summary,
                    lastRunConversationId = j.last_run_conversation_id,
                )
            },
        )

        SyncBundle(
            maxWatermarkMs = watermark,
            conversations = convRecords,
            memories = memRecords,
            jobs = jobRecords,
        )
    }

    override suspend fun applyFromPeer(bundle: SyncBundle): Unit = withContext(ioDispatcher) {
        if (bundle.isEmpty) return@withContext
        for (c in bundle.conversations) {
            conversations.upsertConversationFromPeer(
                id = c.id,
                title = c.title,
                createdAt = c.createdAtEpochMs,
                updatedAt = c.updatedAtEpochMs,
                truncationAck = c.truncationAcknowledgedAt,
                deletedAt = c.deletedAtEpochMs,
            )
            for (m in c.messages) {
                conversations.upsertMessageFromPeer(
                    id = m.id,
                    conversation_id = c.id,
                    role = m.role,
                    content = m.content,
                    tool_call_json = m.toolCallJson,
                    tool_result_json = m.toolResultJson,
                    created_at_epoch_ms = m.createdAtEpochMs,
                    sequence_index = m.sequenceIndex,
                    image_bytes = m.imageBytesBase64?.let { runCatching { Base64.Default.decode(it) }.getOrNull() },
                    render_markdown = if (m.renderMarkdown) 1L else 0L,
                )
            }
        }
        var embedded = 0
        var skipped = 0
        for (mem in bundle.memories) {
            // A tombstoned memory still upserts (to propagate the delete) — but we
            // don't need a real embedding for it; reuse a zero vector to satisfy
            // the NOT NULL column. Live memories must re-embed; skip if we can't.
            val embeddingBlob: ByteArray? = if (mem.deletedAtEpochMs != null) {
                EmbeddingBlob.encode(FloatArray(Memory.EMBEDDING_DIM))
            } else {
                val vector = embedder.embed(mem.text)?.vector
                if (vector == null || vector.size != Memory.EMBEDDING_DIM) {
                    skipped++
                    null
                } else {
                    EmbeddingBlob.encode(vector)
                }
            }
            if (embeddingBlob == null) continue
            memories.upsertMemoryFromPeer(
                id = mem.id,
                text = mem.text,
                category = mem.category,
                conversationId = mem.conversationId,
                createdAt = mem.createdAtEpochMs,
                lastAccessed = mem.lastAccessedEpochMs,
                accessCount = mem.accessCount.toLong(),
                embedding = embeddingBlob,
                expiresAt = mem.expiresAtEpochMs,
                updatedAt = mem.updatedAtEpochMs,
                deletedAt = mem.deletedAtEpochMs,
            )
            embedded++
        }
        var jobsApplied = 0
        var jobsDropped = 0
        for (rec in bundle.jobs) {
            val exists = jobs.selectJobById(rec.id).executeAsOneOrNull() != null
            when (val action = jobPolicy.apply(rec, exists)) {
                JobApplyAction.Drop -> jobsDropped++
                is JobApplyAction.UpsertFull -> {
                    val r = action.record
                    jobs.upsertJobFromPeer(
                        id = r.id,
                        name = r.name,
                        command = r.command,
                        prompt = r.prompt,
                        working_dir = r.workingDir,
                        schedule_type = r.scheduleType,
                        cron_expression = r.cronExpression,
                        fire_at_epoch_ms = r.fireAtEpochMs,
                        paused = if (r.paused) 1L else 0L,
                        created_at_epoch_ms = r.createdAtEpochMs,
                        updated_at_epoch_ms = r.updatedAtEpochMs,
                        deleted_at_epoch_ms = r.deletedAtEpochMs,
                        last_run_status = r.lastRunStatus,
                        last_run_at_epoch_ms = r.lastRunAtEpochMs,
                        last_run_summary = r.lastRunSummary,
                        last_run_conversation_id = r.lastRunConversationId,
                    )
                    jobsApplied++
                }
                is JobApplyAction.PausedOnly -> {
                    val r = action.record
                    jobs.updatePausedFromPeer(
                        paused = if (r.paused) 1L else 0L,
                        updatedAt = r.updatedAtEpochMs,
                        id = r.id,
                    )
                    onJobPausedFromPeer(r.id, r.paused)
                    jobsApplied++
                }
            }
        }
        logger(
            "applied: conversations=${bundle.conversations.size} memories=$embedded " +
                "(skipped $skipped, no embedding) jobs=$jobsApplied (dropped $jobsDropped)",
        )
    }
}
