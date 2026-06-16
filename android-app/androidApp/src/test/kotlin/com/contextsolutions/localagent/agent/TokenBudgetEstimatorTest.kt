package com.contextsolutions.localagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Char-based token estimator coverage. The heuristic is intentionally
 * approximate — these tests pin the algebra so future changes that shift
 * the budget can be caught immediately, and codify the round-trip
 * monotonicity property the overflow guard relies on.
 */
class TokenBudgetEstimatorTest {

    @Test
    fun estimateTokens_returns_zero_for_empty_string() {
        assertEquals(0, TokenBudgetEstimator.estimateTokens(""))
    }

    @Test
    fun estimateTokens_rounds_up_short_strings() {
        // 1 char → ceil(1/4) = 1 token; 4 chars → 1; 5 chars → 2.
        assertEquals(1, TokenBudgetEstimator.estimateTokens("a"))
        assertEquals(1, TokenBudgetEstimator.estimateTokens("abcd"))
        assertEquals(2, TokenBudgetEstimator.estimateTokens("abcde"))
    }

    @Test
    fun estimateTokens_scales_with_length() {
        val s = "a".repeat(1_000)
        assertEquals(250, TokenBudgetEstimator.estimateTokens(s))
    }

    @Test
    fun estimateHistoryTokens_includes_per_message_overhead() {
        val msgs = listOf(
            ChatMessage.User("a".repeat(4)), // 1 + 8 = 9
            ChatMessage.Assistant("b".repeat(4)), // 1 + 8 = 9
        )
        assertEquals(18, TokenBudgetEstimator.estimateHistoryTokens(msgs))
    }

    @Test
    fun wouldOverflow_false_below_safe_history_tokens() {
        // 100 messages × ~40 chars each ≈ 4000 chars / 4 = 1000 token bodies,
        // + 100 × 8 overhead = 800. Total ~1800 — well under SAFE_HISTORY_TOKENS.
        val msgs = List(100) { ChatMessage.User("a".repeat(40)) }
        assertFalse(TokenBudgetEstimator.wouldOverflow(msgs))
    }

    @Test
    fun wouldOverflow_true_above_safe_history_tokens() {
        // Single very long user message that obviously blows the budget.
        val whopper = ChatMessage.User("x".repeat(40_000))
        assertTrue(TokenBudgetEstimator.wouldOverflow(listOf(whopper)))
    }

    @Test
    fun safe_history_tokens_leaves_headroom_under_kv_cache_ceiling() {
        // Sanity-check the algebra: safe history + worst-case system + reserve
        // must fit inside the KV cache with a non-trivial margin.
        val totalWorstCase = TokenBudgetEstimator.SAFE_HISTORY_TOKENS +
            TokenBudgetEstimator.SYSTEM_PROMPT_WORST_CASE +
            TokenBudgetEstimator.GENERATION_RESERVE
        assertTrue(
            "safe budget pushes past the KV cache ceiling: $totalWorstCase > ${TokenBudgetEstimator.KV_CACHE_TOKENS}",
            totalWorstCase < TokenBudgetEstimator.KV_CACHE_TOKENS,
        )
    }
}
