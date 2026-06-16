package com.contextsolutions.localagent.memory

/**
 * Fast syntactic pre-filter for the memory extractor. Returns true when
 * the user message is shaped like a question or recall request — those
 * turns must not be saved as memories.
 *
 * **Why this is needed.** The v1.0 classifier was trained on the
 * convention "USER provides the fact, ASSISTANT acknowledges". When the
 * agent retrieves a memory and the assistant echoes the fact, the
 * classifier's pair-encoded forward pass sees the memorable content in
 * the assistant half and fires HAS_EXTRACTION even though the user
 * contributed nothing new. Since the memory text is always the user
 * message (M5_PLAN.md §2 Q3 — v1 keeps it simple), this writes the
 * QUESTION as a memory ("what is my favorite sports team"). Dedup
 * doesn't save us because question↔statement cosine similarity sits
 * below the 0.85 dedup threshold.
 *
 * Skipping any question-shaped user message catches the bug class
 * cleanly. Explicit "Remember…" / "Forget…" commands run *before* this
 * detector so they're unaffected.
 *
 * False-negative risk (declarative statements that look like questions)
 * is small in practice — e.g. "How I love programming" would skip, but
 * such phrasing is rare and exclamations aren't generally memorable
 * facts about the user.
 */
class QuestionDetector {

    fun isQuestionOrRecall(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.endsWith("?")) return true
        val lower = trimmed.lowercase()
        for (prefix in RECALL_PREFIXES) {
            if (lower.startsWith(prefix)) return true
        }
        val firstWord = lower.takeWhile { it.isLetter() || it == '\'' }
        return firstWord in INTERROGATIVE_STARTS
    }

    private companion object {
        /**
         * Single-word interrogative starts (wh-questions, yes/no questions,
         * modal questions). Contractions included for the common spoken
         * forms. `am` / `are` are guarded by the multi-word prefixes below
         * for cases like "am I going to remember…" — but as bare interrogatives
         * they're rare in chat ("am I right?" still has the trailing `?`).
         */
        private val INTERROGATIVE_STARTS = setOf(
            "what", "what's", "whats",
            "who", "who's", "whos",
            "where", "where's", "wheres",
            "when", "when's", "whens",
            "why", "why's", "whys",
            "how", "how's", "hows",
            "which",
            "is", "are", "am", "was", "were",
            "do", "does", "did",
            "can", "could", "will", "would", "should",
            "has", "have", "had",
            "may", "might", "must",
        )

        /**
         * Imperative recall prefixes — phrases like "tell me X" / "remind me X"
         * (without "Remember that …"; those go through RememberForgetDetector
         * first and never reach here).
         */
        private val RECALL_PREFIXES = listOf(
            "tell me ",
            "show me ",
            "remind me ",
            "let me know ",
            "what about ",
            "how about ",
            "do you know ",
            "do you remember ",
        )
    }
}
