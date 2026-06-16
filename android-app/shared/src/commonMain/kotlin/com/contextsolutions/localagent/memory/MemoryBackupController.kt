package com.contextsolutions.localagent.memory

import com.contextsolutions.localagent.platform.AgentClock
import com.contextsolutions.localagent.telemetry.CounterNames
import com.contextsolutions.localagent.telemetry.TelemetryCounters
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Owns the I/O side of memory export/import. The Compose layer holds
 * the SAF launchers + dialogs; this class handles the URI → JSON
 * round-trip and the destructive override-and-restore loop.
 *
 * **Privacy.** Memory text is written to and read from the user-chosen
 * file URI. This is the only path that intentionally writes memory
 * content off-device; the file lives wherever the user pointed the SAF
 * picker (Drive, Files, Downloads). Telemetry counters bump on
 * success but never include text, count, or any payload (inv. #27).
 *
 * **Override semantics.** Import wipes every existing memory before
 * inserting from the file (design Q3 answer). The two operations run
 * sequentially in a single coroutine on `Dispatchers.IO`; if
 * re-embedding partway through fails for a single row, that row is
 * skipped and the loop continues. There is no rollback — the user
 * was warned via the override dialog before this point.
 */
/**
 * Tiny seam between [MemoryViewModel] and the controller — exists
 * only so host tests can substitute a stub without having to fabricate
 * a `Context`, embedder, and store.
 */
interface MemoryBackupOps {
    suspend fun export(destination: BackupWriter): MemoryBackupController.ExportResult
    suspend fun import(source: BackupReader): MemoryBackupController.ImportResult
}

@OptIn(ExperimentalUuidApi::class)
class MemoryBackupController(
    private val store: MemoryStore,
    private val embedder: EmbedderEngine,
    private val clock: AgentClock,
    private val counters: TelemetryCounters,
    private val config: MemoryConfig,
    private val appVersionName: String,
    private val logger: (String) -> Unit = {},
) : MemoryBackupOps {

    /**
     * Write the current store to [destination]. Returns the number of
     * memories serialized.
     *
     * Throws [BackupException] on I/O failure so the caller can show a
     * single toast for any failure mode. JSON encoding itself can't
     * fail for the well-formed inputs Memory rows produce.
     */
    override suspend fun export(destination: BackupWriter): ExportResult {
        val rows = store.listAll()
        val json = MemoryExportSerializer.encode(
            memories = rows,
            appVersionName = appVersionName,
            embedderModelVersion = EMBEDDER_MODEL_VERSION,
            nowEpochMs = clock.nowEpochMs(),
        )
        try {
            destination.writeText(json)
        } catch (t: Throwable) {
            if (t is BackupException) throw t
            logger("export failed: ${t.message}")
            throw BackupException("Couldn't write the backup file. ${t.message ?: ""}".trim())
        }
        counters.increment(CounterNames.MEMORY_EXPORTED_TOTAL)
        logger("exported ${rows.size} memories")
        return ExportResult(memoryCount = rows.size)
    }

    /**
     * Read [source], validate, wipe the store, and re-embed every
     * entry. Returns counts so the UI can phrase the success toast.
     *
     * Behaviour summary:
     *  - File missing / unreadable → [BackupException]
     *  - JSON malformed or schema_version > CURRENT → [BackupException] with the user-friendly message from [DecodeResult.Invalid]
     *  - Each row that the embedder can't process is skipped; not aborted
     *
     * The "delete then insert" loop is intentionally not transactional
     * across [MemoryStore]; the M5 store API doesn't expose batched
     * transactions. The user has been warned (override dialog) that
     * existing memories will be lost. A future v1.x can wrap this in a
     * SQLDelight `transaction { }` once the store interface allows it.
     */
    override suspend fun import(source: BackupReader): ImportResult {
        val raw = try {
            source.readText()
        } catch (t: Throwable) {
            if (t is BackupException) throw t
            logger("import read failed: ${t.message}")
            throw BackupException("Couldn't read the backup file. ${t.message ?: ""}".trim())
        }

        val decoded = MemoryExportSerializer.decode(raw)
        val export = when (decoded) {
            is DecodeResult.Invalid -> throw BackupException(decoded.message)
            is DecodeResult.Ok -> decoded.export
        }

        // Hard-cap gate (PR#46). Refuse a file that declares more than the
        // cap BEFORE the destructive wipe below — so a too-large import never
        // erases the user's existing memories. Checked against the raw file
        // count (pre-filtering) so the message matches what the user sees.
        val limit = config.maxMemories
        if (export.memories.size > limit) {
            counters.increment(CounterNames.MEMORY_CAP_REACHED_TOTAL)
            logger("import refused: file has ${export.memories.size} > cap $limit")
            throw ImportCapExceededException(limit = limit, found = export.memories.size)
        }

        // The destructive override happens here. Nothing left to undo
        // by this point — the confirm dialog is the user's last chance.
        val previousCount = store.listAll().size
        store.deleteAll()

        var imported = 0
        var skipped = 0
        for (entry in export.memories) {
            val category = MemoryCategory.fromWireName(entry.category)
            if (category == null) {
                skipped += 1
                continue
            }
            val embedding = try {
                embedder.embed(entry.text)?.vector
            } catch (t: Throwable) {
                logger("embed failed during import: ${t.message}")
                null
            }
            if (embedding == null || embedding.size != Memory.EMBEDDING_DIM) {
                skipped += 1
                continue
            }
            val memory = Memory(
                id = "mem-${Uuid.random()}",
                text = entry.text,
                category = category,
                conversationId = entry.conversationId,
                createdAtEpochMs = entry.createdAtEpochMs,
                lastAccessedEpochMs = entry.lastAccessedEpochMs,
                accessCount = entry.accessCount,
                embedding = embedding,
                expiresAtEpochMs = entry.expiresAtEpochMs,
            )
            try {
                store.insert(memory)
                imported += 1
            } catch (t: Throwable) {
                logger("insert failed during import: ${t.message}")
                skipped += 1
            }
        }

        counters.increment(CounterNames.MEMORY_IMPORTED_TOTAL)
        logger("imported=$imported skipped=$skipped replaced=$previousCount")
        return ImportResult(
            importedCount = imported,
            skippedCount = skipped,
            replacedCount = previousCount,
        )
    }

    data class ExportResult(val memoryCount: Int)
    data class ImportResult(
        val importedCount: Int,
        val skippedCount: Int,
        val replacedCount: Int,
    )

    /** Exception type the UI layer flattens into a toast. */
    class BackupException(message: String) : RuntimeException(message)

    /**
     * Import refused because the file declares more memories than the hard
     * cap ([MemoryConfig.maxMemories]). Distinct from [BackupException] so the
     * UI surfaces a dedicated alert dialog (not a toast); thrown BEFORE any
     * destructive wipe, so the existing store is untouched.
     */
    class ImportCapExceededException(val limit: Int, val found: Int) :
        RuntimeException("Import has $found memories, more than the maximum of $limit.")

    private companion object {
        private const val EMBEDDER_MODEL_VERSION = "all-MiniLM-L6-v2-int8-v1.0.0"
    }
}
