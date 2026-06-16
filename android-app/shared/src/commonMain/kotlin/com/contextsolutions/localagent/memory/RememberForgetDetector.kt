package com.contextsolutions.localagent.memory

/**
 * Regex layer for explicit memory commands per PRD §3.2.4 — "remember
 * that I'm allergic to peanuts" / "forget what I said about my job".
 *
 * The shipped classifier (`preflight_memory_shared_v1.0.0`) folds these
 * into the binary `presence` head — its dataset includes
 * `EXPLICIT_REMEMBER` / `EXPLICIT_FORGET` tags but the model doesn't
 * surface them as separate logits. So we keyword-match here and let
 * matches **bypass** the classifier (force-extract for remember,
 * force-delete-by-cosine for forget) per Q2 in M5_PLAN.md §2.
 *
 * Patterns are intentionally narrow — only explicit-prefix forms. Casual
 * phrasings ("yeah save that") fall through to the classifier path. M6
 * telemetry will tell us whether to broaden the regex.
 */
class RememberForgetDetector {

    /**
     * Classify [text] as Remember / Forget / None. The matched prefix is
     * stripped from the payload so callers can use the result directly:
     * "remember that I'm allergic to peanuts" → `Remember("I'm allergic to peanuts")`.
     */
    fun classify(text: String): Command {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Command.None

        val rememberMatch = REMEMBER_REGEX.find(trimmed)
        if (rememberMatch != null) {
            val payload = trimmed.substring(rememberMatch.range.last + 1).trim()
            if (payload.isNotEmpty()) return Command.Remember(payload)
        }

        val forgetMatch = FORGET_REGEX.find(trimmed)
        if (forgetMatch != null) {
            val payload = trimmed.substring(forgetMatch.range.last + 1).trim()
            // "forget it" / "forget that" with nothing after still aborts —
            // we have no signal for what to delete. Fall through to None
            // so the classifier path can decide.
            if (payload.isNotEmpty()) return Command.Forget(payload)
        }

        return Command.None
    }

    sealed interface Command {
        data object None : Command

        /**
         * Explicit "remember …" command. [payload] is the user-supplied
         * statement after the matched prefix. The extractor force-creates
         * a memory with `text = payload` regardless of the classifier's
         * presence verdict.
         */
        data class Remember(val payload: String) : Command

        /**
         * Explicit "forget …" command. [payload] describes what to forget;
         * the extractor embeds it and runs `MemoryStore.deleteByCosine`
         * with the dedup threshold (cosine > 0.85). If no memory clears
         * the threshold the command is silently a no-op.
         */
        data class Forget(val payload: String) : Command
    }

    private companion object {
        // Matches the PREFIX of a remember command. We capture only the
        // fixed prefix portion; the payload is `trimmed.substring(end+1)`.
        // Optional "please " is allowed. Alternation order matters —
        // longer matches first so "i'm" and "i am" don't get truncated to
        // bare "i".
        //
        // Connector vocabulary covers the natural ways people prefix a
        // memorable fact: "remember that …" / "remember I …" / "remember
        // my …" / "remember our …" / "remember the …" / "remember when …"
        // / etc. Slightly permissive: "remember to buy milk" will be
        // captured as a memory, which is mildly wrong but acceptable
        // (user can delete; v1.x can split TODOs from memories if
        // telemetry shows users want it).
        private val REMEMBER_REGEX = Regex(
            "^(?i)(?:please\\s+)?(?:remember|note|save)\\s+" +
                "(?:" +
                "that|this|me|" +
                "i'?m|i\\s+am|i|" +
                "my|our|its|their|his|her|" +
                "the|a|an|" +
                "when|where|how|" +
                "to|about" +
                ")\\b",
        )

        // Forget commands. Includes "forget that I told you about X" / "forget
        // about X" / "delete the memory of X" / "forget my X" forms.
        private val FORGET_REGEX = Regex(
            "^(?i)(?:please\\s+)?(?:forget|delete|drop)\\s+" +
                "(?:" +
                "what\\s+i\\s+(?:said|told\\s+you)\\s+about|" +
                "what\\s+i\\s+said|" +
                "what\\s+i\\s+told\\s+you|" +
                "the\\s+(?:memory|memories|fact)\\s+(?:about|of|that)|" +
                "everything\\s+about|" +
                "about|that|" +
                "i'?m|i\\s+am|" +
                "my|our|its|their|his|her|" +
                "the|a|an" +
                ")\\b",
        )
    }
}
