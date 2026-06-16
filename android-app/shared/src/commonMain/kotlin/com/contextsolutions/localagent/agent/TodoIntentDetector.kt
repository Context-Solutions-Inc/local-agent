package com.contextsolutions.localagent.agent

/**
 * Lightweight keyword check that recognises when a user message is asking
 * the assistant to manage the TODO list. The agent loop uses this to
 * short-circuit pre-flight (no web search for `add buy milk to my todos`)
 * AND to emit a static guidance message when the parser couldn't pin the
 * phrasing down to a specific action — instead of falling through to
 * Gemma, whose TODO responses would be unstructured and unreliable.
 *
 * Keywords are intentionally narrow:
 *  - "todo"/"todos"/"to-do"/"to do" — explicit nouns
 *  - "task"/"tasks" with a verb context — covers "add a task", "complete the task"
 *  - "checklist"/"my list"/"to-do list" — the common phrasing for the surface
 *  - "cross off"/"tick off" — checking off an item
 *
 * Notably absent: bare "remind" (clock territory; see CLOCK invariant in
 * [AgentLoop]). The add-todo regex picks up "remember to" / "don't let me
 * forget" inside [TodoCommandParser]; those don't need to be intent gates.
 */
class TodoIntentDetector {

    fun isTodoIntent(message: String): Boolean {
        val lower = message.lowercase()
        if (DIRECT_KEYWORDS.any { lower.contains(it) }) return true
        // "tasks" alone is too generic — only match when paired with a
        // management verb to avoid false-positives on prose like "we have
        // tasks at work today".
        if (TASK_VERB_REGEX.containsMatchIn(lower)) return true
        return false
    }

    private companion object {
        val DIRECT_KEYWORDS: Set<String> = setOf(
            "todo", "todos",
            "to-do", "to do list",
            "checklist",
            "my list",
            "cross off", "tick off",
        )
        // Verb-then-noun gate. Allow a short scaffolding window between the
        // verb and the `task[s]` noun so "delete the gym task" /
        // "add a high priority task" / "rename my task" all match without
        // demanding a closed scaffolding vocabulary.
        val TASK_VERB_REGEX: Regex = Regex(
            """\b(?:add|create|new|finish|complete|delete|remove|drop|rename|retitle|reschedule|set|change|mark|cross\s+off|tick\s+off)\b[^.!?\n]{0,60}\btask[s]?\b""",
        )
    }
}
