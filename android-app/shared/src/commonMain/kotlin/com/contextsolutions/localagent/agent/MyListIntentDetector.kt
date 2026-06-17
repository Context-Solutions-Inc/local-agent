package com.contextsolutions.localagent.agent

/**
 * Lightweight check that recognises when a user message is about the user's
 * personal "My List" surface. Matching REQUIRES the literal phrase "my list"
 * (PR #99): bare "list" is far too general ("list the planets" must not
 * hijack the surface), and "todo" is dropped entirely because STT mis-hears
 * it — the feature is deliberately phrased "my list".
 *
 * The agent loop uses this to short-circuit pre-flight (no web search for
 * "add buy milk to my list") AND to emit a static guidance message when the
 * parser couldn't pin the phrasing down to a specific action — instead of
 * falling through to Gemma, whose list responses would be unstructured and
 * unreliable.
 */
class MyListIntentDetector {

    fun isMyListIntent(message: String): Boolean = MY_LIST_PHRASE.containsMatchIn(message)

    private companion object {
        // Requires the possessive "my" immediately before the "list" noun
        // ("my list" / "my lists"). A different list ("my shopping list")
        // intentionally does NOT match — only the dedicated surface does.
        val MY_LIST_PHRASE: Regex = Regex("""\bmy\s+lists?\b""", RegexOption.IGNORE_CASE)
    }
}
