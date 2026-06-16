package com.contextsolutions.localagent.agent

/**
 * Cheap word-level heuristic that recognises when a user message is asking
 * the assistant to set/cancel/list a timer or alarm. The agent loop uses it
 * to short-circuit the pre-flight search classifier — the classifier was
 * trained before clock tools existed and sometimes fires a web search for
 * queries like "set a 1-minute timer for tea", which derails Gemma into
 * asking for clarification instead of calling [ClockToolHandler.SET_TIMER_NAME].
 *
 * Intentionally narrow: only triggers on words/phrases that almost always
 * mean a clock action. False positives (treating a non-clock turn as one)
 * just skip pre-flight search; Gemma can still call `web_search` itself.
 * False negatives (missing a real clock turn) are the costly case — the
 * pre-flight may then fire a search and confuse the model — so the keyword
 * set leans inclusive.
 */
class ClockIntentDetector {

    fun isClockIntent(message: String): Boolean {
        val lower = message.lowercase()
        if (KEYWORDS.any { lower.contains(it) }) return true
        // Catch "X minute / min / hour timer" style phrasings that don't
        // contain a standalone "timer" keyword first; covered by KEYWORDS
        // anyway via "timer", but the duration-then-noun pattern is also
        // common in "remind me in 5 minutes" / "in 25 min".
        return REMIND_IN_DURATION.containsMatchIn(lower)
    }

    private companion object {
        val KEYWORDS: Set<String> = setOf(
            "timer", "alarm",
            "wake me", "wake up at",
            "set a reminder",
            "snooze",
        )
        // "remind me in 5 minutes", "in 25 min", "in an hour" — anchored to
        // a leading "in" so it doesn't catch "5 minutes ago".
        val REMIND_IN_DURATION: Regex = Regex(
            """\bin\s+\d+\s*(s|sec|second|seconds|m|min|minute|minutes|h|hr|hour|hours)\b""",
        )
    }
}
