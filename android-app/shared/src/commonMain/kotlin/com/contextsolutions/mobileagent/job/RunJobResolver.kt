package com.contextsolutions.mobileagent.job

/**
 * Splits a stripped "run job" remainder ("property search Westport, Ontario")
 * into the job it names and the trailing keyword(s) (PR #88, invariant #59).
 *
 * **Longest-name token-prefix match.** Job names can be multiple words
 * ("Property Search"), so we tokenize both the remainder and each live job name
 * and match when the name's tokens are a leading prefix of the remainder's
 * tokens. The LONGEST matching name wins, so "Property Search Pro" beats
 * "Property Search" when both lead. The remaining tokens (original-cased) are the
 * keyword(s); tombstoned jobs are skipped.
 */
class RunJobResolver {

    fun resolve(remainder: String, jobs: List<Job>): RunJobResolution {
        val tokens = remainder.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return RunJobResolution.NotFound("")
        val lowerTokens = tokens.map { it.lowercase() }

        var best: Job? = null
        var bestLen = -1
        for (job in jobs) {
            if (job.deletedAtEpochMs != null) continue
            val nameTokens = job.name.trim().split(WHITESPACE)
                .filter { it.isNotEmpty() }
                .map { it.lowercase() }
            if (nameTokens.isEmpty() || nameTokens.size > tokens.size) continue
            if (lowerTokens.subList(0, nameTokens.size) == nameTokens && nameTokens.size > bestLen) {
                best = job
                bestLen = nameTokens.size
            }
        }

        val job = best ?: return RunJobResolution.NotFound(remainder.trim())
        // Keywords = the tokens after the matched name, original-cased so a
        // value like "Westport, Ontario" keeps its casing + comma.
        val keywords = tokens.drop(bestLen).joinToString(" ")
        return RunJobResolution.Match(job, keywords)
    }

    private companion object {
        val WHITESPACE: Regex = Regex("\\s+")
    }
}

/** Outcome of resolving a "run job …" remainder against the live job list. */
sealed interface RunJobResolution {
    /** A job whose name leads the remainder; [keywords] is the rest (may be blank). */
    data class Match(val job: Job, val keywords: String) : RunJobResolution

    /** No live job name leads the remainder. [requestedText] is what the user typed (blank if none). */
    data class NotFound(val requestedText: String) : RunJobResolution
}
