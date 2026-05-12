package com.contextsolutions.mobileagent.memory

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire format for the memory backup file. Text + category + metadata
 * only — embeddings are recomputed on import (per PR#5 design Q1
 * answer). This means the file is small (~250 bytes per memory) and
 * tolerates an embedder model swap, at the cost of a one-shot
 * `embedder.embed()` per row at import time (~40 ms on Pixel 7 CPU).
 *
 * [schemaVersion] is the schema of THIS file (the on-disk JSON), not
 * the SQLite schema. v1 is what ships in PR#5. Future versions of the
 * app may bump it (e.g. to v2 if Gemma-canonical text replaces the
 * verbatim user-message text per the M5_M6_HANDOFF v1.x note); on
 * import, the receiving build rejects any file whose `schemaVersion`
 * exceeds its `MemoryExport.CURRENT_SCHEMA_VERSION` — design Q4
 * answer.
 *
 * [appVersionName] and [embedderModelVersion] are informational —
 * they don't gate import behaviour today, but they're useful in
 * support / debugging conversations.
 */
@Serializable
data class MemoryExport(
    @SerialName("schema_version")
    val schemaVersion: Int,

    @SerialName("app_version_name")
    val appVersionName: String,

    @SerialName("embedder_model_version")
    val embedderModelVersion: String,

    @SerialName("exported_at_epoch_ms")
    val exportedAtEpochMs: Long,

    @SerialName("memories")
    val memories: List<MemoryExportEntry>,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

/**
 * A single memory row. Mirrors [Memory] minus the embedding (regenerated
 * on import) and minus `id` (a fresh UUID is assigned on import so two
 * backups from different devices can be merged later without id
 * collisions; v1 always overrides the existing store anyway).
 */
@Serializable
data class MemoryExportEntry(
    @SerialName("text")
    val text: String,

    @SerialName("category")
    val category: String,

    @SerialName("conversation_id")
    val conversationId: String? = null,

    @SerialName("created_at_epoch_ms")
    val createdAtEpochMs: Long,

    @SerialName("last_accessed_epoch_ms")
    val lastAccessedEpochMs: Long,

    @SerialName("access_count")
    val accessCount: Int,

    @SerialName("expires_at_epoch_ms")
    val expiresAtEpochMs: Long? = null,
)

/**
 * Pure serializer: no I/O, no clock, no embedder calls. The caller
 * (Android-side BackupController) handles SAF URIs and the re-embed
 * loop. Tests live in :androidApp/src/test/ per the project test
 * convention.
 */
object MemoryExportSerializer {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Build the on-disk JSON string for a list of [memories]. Drops the
     * embedding (re-derived on import) and the id (re-assigned).
     */
    fun encode(
        memories: List<Memory>,
        appVersionName: String,
        embedderModelVersion: String,
        nowEpochMs: Long,
    ): String {
        val export = MemoryExport(
            schemaVersion = MemoryExport.CURRENT_SCHEMA_VERSION,
            appVersionName = appVersionName,
            embedderModelVersion = embedderModelVersion,
            exportedAtEpochMs = nowEpochMs,
            memories = memories.map { it.toExportEntry() },
        )
        return json.encodeToString(MemoryExport.serializer(), export)
    }

    /**
     * Parse and validate. Returns [DecodeResult] so the UI layer can
     * surface a clear error to the user without depending on
     * kotlinx-serialization exception types.
     */
    fun decode(raw: String): DecodeResult {
        val parsed = try {
            json.decodeFromString(MemoryExport.serializer(), raw)
        } catch (t: Throwable) {
            return DecodeResult.Invalid("Couldn't read the backup file: ${t.message ?: "malformed JSON"}")
        }
        if (parsed.schemaVersion > MemoryExport.CURRENT_SCHEMA_VERSION) {
            return DecodeResult.Invalid(
                "This backup was made by a newer version of the app (schema v${parsed.schemaVersion}). Update the app and try again.",
            )
        }
        if (parsed.schemaVersion < 1) {
            return DecodeResult.Invalid("Unsupported backup schema: v${parsed.schemaVersion}.")
        }
        return DecodeResult.Ok(parsed)
    }

    private fun Memory.toExportEntry(): MemoryExportEntry = MemoryExportEntry(
        text = text,
        category = category.wireName,
        conversationId = conversationId,
        createdAtEpochMs = createdAtEpochMs,
        lastAccessedEpochMs = lastAccessedEpochMs,
        accessCount = accessCount,
        expiresAtEpochMs = expiresAtEpochMs,
    )
}

/** Outcome of [MemoryExportSerializer.decode]. */
sealed interface DecodeResult {
    data class Ok(val export: MemoryExport) : DecodeResult
    data class Invalid(val message: String) : DecodeResult
}
