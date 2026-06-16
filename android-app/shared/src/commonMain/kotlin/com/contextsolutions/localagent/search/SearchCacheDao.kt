package com.contextsolutions.localagent.search

import com.contextsolutions.localagent.db.SearchCacheQueries
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * SQLite-backed search cache (PRD §3.4). Wraps the generated [SearchCacheQueries]
 * so the agent loop deals in [FormattedSearchPayload] rather than raw column sets.
 *
 *  - [lookup] returns the cached payload if and only if the row is non-expired;
 *    on a hit it bumps `last_accessed_at_epoch_ms` so LRU eviction tracks usage.
 *  - [store] runs [SearchCacheQueries.evictLruWhenAboveCap] after every insert
 *    to keep the table bounded at [DEFAULT_MAX_ENTRIES] (PRD §3.4: 500). It also
 *    sweeps expired rows opportunistically so the DB doesn't grow unbounded with
 *    stale time-sensitive entries that fall out of the LRU window.
 *  - [clear] truncates the table (settings UI hook).
 */
class SearchCacheDao(
    private val queries: SearchCacheQueries,
    private val nowEpochMs: () -> Long,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val sourceListSerializer = ListSerializer(SearchSource.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    fun lookup(query: String): CacheLookup {
        val normalized = SearchCacheKey.normalize(query)
        val now = nowEpochMs()
        val row = queries.selectCacheEntry(normalized, now).executeAsOneOrNull()
            ?: return CacheLookup.Miss
        queries.updateLastAccessed(now, normalized)
        val sources = runCatching { json.decodeFromString(sourceListSerializer, row.payload_json) }
            .getOrElse {
                // A corrupt row shouldn't poison future lookups — drop it and miss.
                queries.clearAllEntries()
                return CacheLookup.Miss
            }
        return CacheLookup.Hit(
            FormattedSearchPayload(json = row.payload_json, sources = sources),
        )
    }

    fun store(query: String, payload: FormattedSearchPayload) {
        val normalized = SearchCacheKey.normalize(query)
        val category = SearchCacheClassifier.categorize(query)
        val now = nowEpochMs()
        queries.transaction {
            queries.deleteExpiredEntries(now)
            queries.insertCacheEntry(
                normalized_query = normalized,
                original_query = query,
                payload_json = payload.json,
                category = category.storageId,
                cached_at_epoch_ms = now,
                last_accessed_at_epoch_ms = now,
                expires_at_epoch_ms = now + category.ttlMs,
            )
            queries.evictLruWhenAboveCap(maxEntries.toLong())
        }
    }

    fun clear() {
        queries.clearAllEntries()
    }

    fun count(): Long = queries.countEntries().executeAsOne()

    sealed interface CacheLookup {
        data object Miss : CacheLookup
        data class Hit(val payload: FormattedSearchPayload) : CacheLookup
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 500
    }
}
