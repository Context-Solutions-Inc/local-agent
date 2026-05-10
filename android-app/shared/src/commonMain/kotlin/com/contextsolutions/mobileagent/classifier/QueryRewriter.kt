package com.contextsolutions.mobileagent.classifier

import com.contextsolutions.mobileagent.agent.TimeContext
import com.contextsolutions.mobileagent.memory.Memory
import com.contextsolutions.mobileagent.memory.MemoryCategory
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/**
 * Deterministic-only query rewriter for the Phase C [PreflightRouter] —
 * resolves relative time expressions in user queries to concrete dates
 * using the same [TimeContext] the prompt assembler injects, and aborts
 * (returns `null`) on queries that need memory context to disambiguate.
 *
 * **PRD §3.2.1 v1 scope (M4_PLAN.md §2 ratified):**
 *
 *  - Date/time substitution from [TimeContext]: today, tonight, this morning,
 *    this afternoon, this evening, this week, this month, this year,
 *    yesterday, last night, last week, last month, last year.
 *  - Memory-reference abort: queries containing possessives ("my team",
 *    "my company", "where I live", …) return `null` so the router emits
 *    `FallThrough(RewriterAbort)`. M5 will replace this with retrieval-
 *    backed substitution.
 *  - Empty / one-token output → `null` (nothing left to search for).
 *
 * **Out of scope for v1:** Gemma fallback for complex rewrites (defeats the
 * round-trip-saving purpose), abbreviation expansion (search engines handle
 * NFL/S&P/etc. fine; revisit if telemetry shows under-performance).
 *
 * The rewriter is pure-Kotlin and lives in commonMain so iOS gets it for
 * free in Phase 2.
 */
class QueryRewriter(
    private val timeContextProvider: () -> TimeContext,
) {

    /**
     * Returns the rewritten query, or `null` to abort and FallThrough.
     *
     * @param memories optional retrieved memories. When non-empty, the
     *   rewriter will try to substitute possessives ("my team", "where I
     *   live", …) with a span extracted from the matching memory before
     *   falling through. This is the M5 promotion path described in
     *   PRD §3.2.1: "did my team win" with an Eagles preference memory
     *   becomes a normal high-band FireSearch with a concrete query.
     */
    fun rewrite(originalQuery: String, memories: List<Memory> = emptyList()): String? {
        val trimmed = originalQuery.trim()
        if (trimmed.isEmpty()) return null

        val substituted = applyPossessiveSubstitutions(trimmed, memories)

        if (containsMemoryReference(substituted)) return null

        val context = timeContextProvider()
        val rewritten = applyDateTimeSubstitutions(substituted, context)

        val collapsed = rewritten.replace(Regex("\\s+"), " ").trim()
        if (collapsed.isEmpty()) return null
        if (collapsed.split(' ').size < 2) return null

        return collapsed
    }

    // -- Possessive substitution ------------------------------------------

    /**
     * Walk [POSSESSIVE_RULES] in order; for the first rule whose regex
     * matches and whose category has a memory in [memories], replace the
     * matched span with the extracted memory tail. Multiple rules can fire
     * (e.g., a query mentioning both "my team" and "my city") — each is
     * resolved independently with the most-similar memory of that category.
     *
     * If extraction fails (no marker in the memory text, or the tail is
     * too long to be a clean noun phrase), the rule is skipped and the
     * possessive falls through to the abort regex below.
     */
    private fun applyPossessiveSubstitutions(query: String, memories: List<Memory>): String {
        if (memories.isEmpty()) return query
        var result = query
        for (rule in POSSESSIVE_RULES) {
            if (!rule.regex.containsMatchIn(result)) continue
            val memory = memories.firstOrNull { it.category == rule.category } ?: continue
            val span = extractSubstitutionSpan(memory.text) ?: continue
            result = rule.regex.replace(result, span)
        }
        return result
    }

    /**
     * Extract a noun-phrase tail from the user's verbatim memory text.
     * v1's templated extraction (M5_PLAN.md §2 Q3) stores the user's whole
     * disclosure as the memory text — e.g. "my favorite nfl team is the
     * philadelphia eagles" — so we need a heuristic to pull "philadelphia
     * eagles" out of it.
     *
     * Strategy: find the last copula/preposition marker, take everything
     * after it, strip punctuation. If the tail is empty or implausibly long
     * (>5 tokens — likely a multi-clause sentence), give up. Brittle by
     * design; v1.x replaces with Gemma-generated canonical memory text.
     */
    private fun extractSubstitutionSpan(memoryText: String): String? {
        val lower = memoryText.lowercase()
        var bestIdx = -1
        var bestMarkerLen = 0
        for (marker in SPAN_MARKERS) {
            val idx = lower.lastIndexOf(marker)
            if (idx > bestIdx) {
                bestIdx = idx
                bestMarkerLen = marker.length
            }
        }
        if (bestIdx < 0) return null
        val tail = memoryText.substring(bestIdx + bestMarkerLen)
            .trim()
            .trimEnd(',', '.', '!', '?', ';', ':')
        if (tail.isEmpty()) return null
        // Reject "we played hard last night" — too sentence-like for a
        // possessive substitution. 5 tokens is enough headroom for
        // "philadelphia eagles" / "the new york knicks" / "google in mountain
        // view california" without swallowing whole clauses.
        if (tail.split(Regex("\\s+")).size > 5) return null
        return tail
    }

    // -- Memory-reference detection ----------------------------------------

    /**
     * Conservative match: any "my X" possessive aborts, plus a small set of
     * first-person spatial/temporal phrases. False positives just send a
     * query to standard Gemma tool-calling instead of pre-flight, which is
     * the documented fallback (PRD §3.2.1). False negatives — rewriting a
     * memory-dependent query into nonsense — are the worse failure mode.
     */
    private fun containsMemoryReference(query: String): Boolean {
        val lower = query.lowercase()
        return MEMORY_REFERENCE_REGEX.containsMatchIn(lower)
    }

    // -- Date/time substitution --------------------------------------------

    /**
     * Apply the rules in **most-specific-first** order so multi-word phrases
     * ("last night", "last week") are caught before their constituent
     * single words ("last" alone is never substituted).
     */
    private fun applyDateTimeSubstitutions(query: String, context: TimeContext): String {
        var result = query
        for (rule in dateTimeRules(context)) {
            result = rule.regex.replace(result, rule.replacement)
        }
        return result
    }

    private fun dateTimeRules(context: TimeContext): List<RewriteRule> {
        val today = LocalDate(context.now.year, context.now.monthNumber, context.now.dayOfMonth)
        val yesterday = today.minus(DatePeriod(days = 1))
        val lastWeekStart = today.minus(DatePeriod(days = 7))
        val lastMonthFirst = today.minus(DatePeriod(months = 1))

        // Order matters: longer phrases first.
        return listOf(
            RewriteRule(wordRegex("last night"), iso(yesterday) + " evening"),
            RewriteRule(wordRegex("yesterday evening"), iso(yesterday) + " evening"),
            RewriteRule(wordRegex("yesterday morning"), iso(yesterday) + " morning"),
            RewriteRule(wordRegex("yesterday afternoon"), iso(yesterday) + " afternoon"),
            RewriteRule(wordRegex("yesterday"), iso(yesterday)),
            RewriteRule(wordRegex("this morning"), iso(today) + " morning"),
            RewriteRule(wordRegex("this afternoon"), iso(today) + " afternoon"),
            RewriteRule(wordRegex("this evening"), iso(today) + " evening"),
            RewriteRule(wordRegex("tonight"), iso(today) + " evening"),
            RewriteRule(wordRegex("today"), iso(today)),
            RewriteRule(wordRegex("last week"), "week of " + iso(lastWeekStart)),
            RewriteRule(wordRegex("this week"), "week of " + iso(today)),
            RewriteRule(wordRegex("last month"), monthName(lastMonthFirst.monthNumber) + " " + lastMonthFirst.year),
            RewriteRule(wordRegex("this month"), monthName(today.monthNumber) + " " + today.year),
            RewriteRule(wordRegex("last year"), (today.year - 1).toString()),
            RewriteRule(wordRegex("this year"), today.year.toString()),
        )
    }

    private fun iso(date: LocalDate): String =
        "${date.year.toString().padStart(4, '0')}-${date.monthNumber.toString().padStart(2, '0')}-${date.dayOfMonth.toString().padStart(2, '0')}"

    private fun monthName(n: Int): String = when (n) {
        1 -> "January"; 2 -> "February"; 3 -> "March"; 4 -> "April"
        5 -> "May"; 6 -> "June"; 7 -> "July"; 8 -> "August"
        9 -> "September"; 10 -> "October"; 11 -> "November"; 12 -> "December"
        else -> error("invalid month $n")
    }

    private data class RewriteRule(val regex: Regex, val replacement: String)

    private fun wordRegex(phrase: String): Regex =
        Regex("(?i)\\b" + Regex.escape(phrase) + "\\b")

    private data class PossessiveRule(val regex: Regex, val category: MemoryCategory)

    private companion object {
        // Matches first-person possessives + a small set of "where I"/"when I"
        // constructs that strongly suggest the query needs memory context to
        // disambiguate. Intentionally permissive — false positives just route
        // to standard Gemma tool-calling, which is the documented fallback.
        private val MEMORY_REFERENCE_REGEX = Regex(
            """\b(?:""" +
                "my\\s+\\w+|" +                 // my X (any noun)
                "where\\s+i\\s+(?:live|work|study)|" +
                "the\\s+place\\s+where\\s+i\\s+(?:live|work|study)|" +
                "when\\s+i\\s+(?:was|got|moved|started|joined)|" +
                "i\\s+(?:live|work|study)\\s+(?:in|at|for)" +
                """)\b"""
        )

        // Possessive → category mapping (M5_PLAN.md §4 Phase C).
        // Order matters: more specific patterns are checked first because
        // each rule mutates the query in place. "my team" is matched before
        // any catch-all `\bmy\s+\w+\b` (which still lives in the abort
        // regex above to short-circuit unhandled possessives).
        private val POSSESSIVE_RULES: List<PossessiveRule> = listOf(
            // Sports — preference category
            PossessiveRule(Regex("(?i)\\bmy\\s+team\\b"), MemoryCategory.PREFERENCE),
            PossessiveRule(Regex("(?i)\\bmy\\s+(favorite\\s+)?(?:sports?\\s+)?team\\b"), MemoryCategory.PREFERENCE),
            // Professional — employer / workplace
            PossessiveRule(Regex("(?i)\\bmy\\s+(?:company|employer|workplace|job)\\b"), MemoryCategory.PROFESSIONAL),
            PossessiveRule(Regex("(?i)\\bwhere\\s+i\\s+work\\b"), MemoryCategory.PROFESSIONAL),
            // Identity — location
            PossessiveRule(Regex("(?i)\\bwhere\\s+i\\s+live\\b"), MemoryCategory.PERSONAL_IDENTITY),
            PossessiveRule(Regex("(?i)\\bmy\\s+(?:city|hometown|home\\s+town|neighborhood|neighbourhood)\\b"), MemoryCategory.PERSONAL_IDENTITY),
            // Relationship — partners / kids / pets
            PossessiveRule(
                Regex("(?i)\\bmy\\s+(?:partner|spouse|wife|husband|girlfriend|boyfriend|gf|bf)\\b"),
                MemoryCategory.RELATIONSHIP,
            ),
            PossessiveRule(
                Regex("(?i)\\bmy\\s+(?:dog|cat|pet)\\b"),
                MemoryCategory.RELATIONSHIP,
            ),
            PossessiveRule(
                Regex("(?i)\\bmy\\s+(?:kid|kids|son|daughter|child|children)\\b"),
                MemoryCategory.RELATIONSHIP,
            ),
        )

        // Span markers used by [extractSubstitutionSpan] — looked up in
        // MEMORY-text-lowered order; whichever appears LAST wins (so for
        // "my favorite nfl team is the philadelphia eagles" we extract the
        // tail after " is the " rather than after " is "). Markers include
        // a leading and trailing space so they only match between word
        // boundaries — avoids spurious hits inside words.
        private val SPAN_MARKERS: List<String> = listOf(
            " is the ", " are the ", " was the ", " were the ",
            " is ", " are ", " was ", " were ",
            " named ", " called ",
            " at ", " for ", " in ", " from ", " of ",
        )
    }
}
