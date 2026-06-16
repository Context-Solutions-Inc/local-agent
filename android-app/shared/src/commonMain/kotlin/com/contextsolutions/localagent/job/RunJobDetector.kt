package com.contextsolutions.localagent.job

/**
 * Detects an EXPLICIT "run job …" command at the START of a chat message (PR #88,
 * invariant #59). The user's deterministic escape hatch for firing a desktop job
 * inline and feeding its output to the chat LLM, mirroring the explicit web-search
 * force-fire ([com.contextsolutions.localagent.search.ExplicitSearchDetector],
 * #43): the message is `run job <job name> <keyword(s)>`, e.g.
 * "run job property search Westport, Ontario".
 *
 * **Anchored, not contains.** Like the search detector, this matches ONLY a
 * leading command. The anchor is the false-positive guard: "how do I run jobs in
 * cron" does NOT fire because the command isn't at the front, and "run jobs"
 * (plural) does NOT fire because `job\b` requires a word boundary that "jobs"
 * doesn't provide.
 *
 * **Strip before resolving.** The command words aren't part of the job name or
 * keywords, so [stripPrefix] removes them; the remainder ("property search
 * Westport, Ontario") is handed to [RunJobResolver] to split the longest-matching
 * job name from the trailing keyword(s).
 */
class RunJobDetector {

    /** True when [query] opens with a "run job" command. */
    fun matches(query: String): Boolean =
        PREFIX_PATTERN.containsMatchIn(query.trimStart().lowercase())

    /**
     * Removes the leading "run job" command (+ the separating whitespace/colon)
     * and returns the remainder, trimmed and original-cased (so a job name and
     * keywords keep their casing). Returns "" when [matches] is false or nothing
     * follows the command — the caller then reports "job not found".
     */
    fun stripPrefix(query: String): String {
        val trimmed = query.trimStart()
        val match = PREFIX_PATTERN.find(trimmed.lowercase()) ?: return ""
        return trimmed.substring(match.range.last + 1).trim()
    }

    private companion object {
        // Anchored at start (^). `job\b` rejects "run jobs" (no boundary before
        // the trailing 's'); the trailing group consumes a colon or the run of
        // whitespace separating the command from the job name.
        val PREFIX_PATTERN: Regex = Regex("^\\s*run\\s+job\\b(\\s*:|\\s+|$)")
    }
}
