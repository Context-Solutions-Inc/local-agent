package com.contextsolutions.localagent.agent

/**
 * Char-based token-budget heuristic for Gemma 4 E2B running under
 * LiteRT-LM 0.10.2.
 *
 * **Why a heuristic and not a real tokenizer.** LiteRT-LM 0.10.2 does not
 * expose Gemma's SentencePiece tokenizer (CLAUDE.md invariant #1 family).
 * The classifier's WordPiece tokenizer at `assets/vocab.txt` is the
 * DistilBERT vocab — wrong model. Until LiteRT-LM ships a tokenizer API,
 * 4 chars/token (`~6 chars/token` would be safer for English but 4 is the
 * Anthropic / OpenAI ballpark that overcounts slightly, which is the right
 * direction for a budget check).
 *
 * **Why 6,500 and not 6,698.** Algebraically the conversation history can
 * use `8192 − SYSTEM_PROMPT_WORST_CASE (470) − GENERATION_RESERVE (1024)
 * = 6,698`. We round down to 6,500 so the char heuristic's drift on
 * non-English text or unusual punctuation doesn't push us over the
 * actual KV-cache ceiling. PR#13 design lock.
 */
object TokenBudgetEstimator {
    /** Hard ceiling from `InferenceConfig.kvCacheTokens` (PRD §4.2). */
    const val KV_CACHE_TOKENS: Int = 8_192

    /** Worst-case system-prompt size in tokens (SYSTEM_PROMPT.md §10). */
    const val SYSTEM_PROMPT_WORST_CASE: Int = 470

    /** Tokens reserved for the model's reply on this turn. */
    const val GENERATION_RESERVE: Int = 1_024

    /**
     * Safe budget for `history + current user message + tool results` before
     * the overflow guard should fire. Hand-picked to leave a ~200-token
     * margin below the algebraic ceiling for heuristic drift.
     */
    const val SAFE_HISTORY_TOKENS: Int = 6_500

    /** Average chars-per-token for English-ish text under Gemma's SP tokenizer. */
    const val CHARS_PER_TOKEN: Int = 4

    /** Per-message overhead (role tag, separators, etc.) included by Gemma's chat template. */
    const val PER_MESSAGE_OVERHEAD_TOKENS: Int = 8

    /** Round-up division so an empty string is still 0 tokens, a 1-char string is 1. */
    fun estimateTokens(text: String): Int =
        if (text.isEmpty()) 0 else (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    /**
     * Estimate the total token cost of a conversation history list, including
     * per-message overhead for the chat template Gemma applies internally.
     */
    fun estimateHistoryTokens(history: List<ChatMessage>): Int =
        history.sumOf { estimateTokens(it.text) + PER_MESSAGE_OVERHEAD_TOKENS }

    /**
     * True when sending [historyWithPendingUserMessage] (the persisted
     * history plus the user's next message) would exceed [SAFE_HISTORY_TOKENS].
     */
    fun wouldOverflow(historyWithPendingUserMessage: List<ChatMessage>): Boolean =
        estimateHistoryTokens(historyWithPendingUserMessage) > SAFE_HISTORY_TOKENS
}
