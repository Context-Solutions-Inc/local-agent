package com.contextsolutions.mobileagent.memory

/**
 * Three-tier eviction cascade per PRD §3.2.4. Runs **before** every
 * insert; stops as soon as the store is back under [capacity].
 *
 *  - **Tier 1** — drop expired rows (`expires_at <= now`). Cleans up
 *    `temporary_context` memories the user no longer cares about.
 *  - **Tier 2** — drop rows untouched in [staleMs] (default 90 days). Both
 *    `last_accessed_epoch_ms` and the index on it make this cheap.
 *  - **Tier 3** — LRU + access-frequency. `selectLruEvictionCandidateIds`
 *    orders by `(last_accessed ASC, access_count ASC)` so the rows
 *    least-recently-AND-least-often accessed go first.
 *
 * Each tier only runs if the previous one didn't claw the count below
 * [capacity]. Tier 3 always succeeds (worst case: store is full of
 * recently-accessed rows; we delete by the LRU+freq sort until count
 * drops). The store's `count(now)` filters expired so tier 2/3 work
 * against the post-tier-1 view automatically.
 *
 * The default [capacity] of 1,000 matches PRD §3.2.4. When (M5_PLAN.md
 * §8) we expose a "memory cap" setting in v1.x, this is where it threads
 * in.
 */
open class MemoryEvictor(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val staleMs: Long = DEFAULT_STALE_MS,
    private val logger: (String) -> Unit = {},
) {

    /**
     * Run the cascade if [store]'s non-expired count meets or exceeds
     * [capacity]. Returns the number of rows deleted across all tiers.
     */
    open suspend fun maybeEvict(store: MemoryStore, now: Long): EvictionReport {
        val initial = store.count(now)
        if (initial < capacity) return EvictionReport.NoOp

        var deletedExpired = 0
        var deletedStale = 0
        var deletedLru = 0

        // -- Tier 1: expired -----------------------------------------
        deletedExpired = store.deleteExpired(now)
        var current = store.count(now)
        if (current < capacity) {
            return logged(deletedExpired, deletedStale, deletedLru)
        }

        // -- Tier 2: stale (older than `staleMs`) --------------------
        // Use a candidate list + per-id delete so the store stays a
        // straightforward DAO (no bulk-delete-by-cutoff query needed).
        // `Int.MAX_VALUE` as the limit captures every stale row in one
        // pass.
        val staleCutoff = now - staleMs
        val staleIds = store.selectLruEvictionCandidateIds(staleCutoff, Int.MAX_VALUE)
        for (id in staleIds) {
            store.deleteById(id)
            deletedStale += 1
        }
        current = store.count(now)
        if (current < capacity) {
            return logged(deletedExpired, deletedStale, deletedLru)
        }

        // -- Tier 3: LRU + access-frequency --------------------------
        // Drop the (current - capacity + 1) least-recently-accessed
        // rows so the next insert lands at exactly capacity-1. Use
        // `Long.MAX_VALUE` so every row is eligible regardless of
        // last_accessed timestamp; the SQL ordering handles the rest.
        val overshoot = (current - capacity) + 1
        val lruIds = store.selectLruEvictionCandidateIds(Long.MAX_VALUE, overshoot)
        for (id in lruIds) {
            store.deleteById(id)
            deletedLru += 1
        }

        return logged(deletedExpired, deletedStale, deletedLru)
    }

    private fun logged(expired: Int, stale: Int, lru: Int): EvictionReport {
        val total = expired + stale + lru
        if (total > 0) {
            logger("[memory-evict] expired=$expired stale=$stale lru=$lru total=$total")
        }
        return EvictionReport.Ran(
            deletedExpired = expired,
            deletedStale = stale,
            deletedLru = lru,
        )
    }

    sealed interface EvictionReport {
        data object NoOp : EvictionReport
        data class Ran(
            val deletedExpired: Int,
            val deletedStale: Int,
            val deletedLru: Int,
        ) : EvictionReport {
            val total: Int get() = deletedExpired + deletedStale + deletedLru
        }
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 1_000
        const val DEFAULT_STALE_MS: Long = 90L * 24 * 60 * 60 * 1_000  // 90 days
    }
}
