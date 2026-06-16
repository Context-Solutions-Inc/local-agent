package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.todo.TodoPriority
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Deterministic regex parser for unambiguous TODO commands. Modelled on
 * [ClockCommandParser] — runs in [AgentLoop.run] BEFORE the LLM so we never
 * pay for Gemma's reliability cost (number mangling, mis-categorisation,
 * etc.) on a TODO turn. On any partial match the caller falls back to a
 * static guidance string; we deliberately do NOT defer to the LLM.
 *
 * Coverage is intentionally narrow:
 *  - Add: `add <title>` / `remember to <title>` / `i need to <title>`, with
 *    optional priority and absolute-or-keyword due date.
 *  - List: `list todos`, `what's on my todo list`.
 *  - Complete / uncomplete: `complete #N`, `mark <ref> done`, `reopen #N`.
 *  - Delete: `delete #N`, `remove <title-substring>`.
 *  - Edit: `set #N to high priority`, `move #N to tomorrow`, `rename #N to <new>`.
 *  - Clear completed: `clear completed todos`.
 *
 * Due dates accept `today`, `tomorrow`, and ISO `YYYY-MM-DD`. Anything more
 * natural-language than that intentionally falls through to a partial-match
 * guidance reply rather than to Gemma.
 */
class TodoCommandParser(
    private val clock: Clock = Clock.System,
    private val timeZoneProvider: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {

    fun parse(message: String): TodoCommand? {
        val raw = message.trim()
        if (raw.isEmpty()) return null
        val msg = LEADING_SCAFFOLDING_REGEX.replaceFirst(raw, "").ifBlank { raw }
        // Order: most-specific first. Clear-completed before delete (which
        // would otherwise eat "delete completed todos"). Priority/due-date
        // are anchored on disjoint trailing tokens so order between them
        // doesn't matter, but "add" runs last because it's the most general
        // verb.
        return parseClearCompleted(msg)
            ?: parseList(msg)
            ?: parseComplete(msg)
            ?: parseUncomplete(msg)
            ?: parseSetPriority(msg)
            ?: parseSetDueDate(msg)
            ?: parseRename(msg)
            ?: parseDelete(msg)
            ?: parseAdd(msg)
    }

    // ---- clear completed ----------------------------------------------------

    private val CLEAR_COMPLETED_REGEX = Regex(
        """^\s*(?:clear|delete|remove|wipe)\s+(?:all\s+)?(?:my\s+|the\s+)?(?:completed|finished|done|checked[- ]off)\s+(?:todos?|tasks|items)\s*\.?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseClearCompleted(msg: String): TodoCommand? =
        if (CLEAR_COMPLETED_REGEX.matches(msg)) TodoCommand.ClearCompleted else null

    // ---- list ---------------------------------------------------------------

    private val LIST_REGEX = Regex(
        """^\s*(?:(?:what(?:'?s|\s+is|\s+are)?(?:\s+on)?|which|tell\s+me(?:\s+about)?|do\s+i\s+have|show|list|get\s+me|how\s+many)\s+)?(?:my\s+|the\s+|are\s+my\s+|on\s+my\s+)?(?:current\s+|active\s+|outstanding\s+|incomplete\s+|open\s+|all\s+|completed\s+|finished\s+|done\s+)?(?:todos?|to[- ]?do(?:\s+list)?|tasks|checklist)(?:\s+(?:do\s+i\s+have|are\s+(?:left|open|done|completed)))?\s*\??\s*\.?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseList(msg: String): TodoCommand? {
        if (!LIST_REGEX.matches(msg)) return null
        val lower = msg.lowercase()
        val includeCompleted = lower.contains("all") ||
            lower.contains("completed") ||
            lower.contains("finished") ||
            lower.contains("done")
        return TodoCommand.List(includeCompleted = includeCompleted)
    }

    // ---- complete / uncomplete ---------------------------------------------

    private val COMPLETE_VERB_REGEX = Regex(
        """^\s*(?:complete|finish|check\s+off|cross\s+off|tick\s+off|done\s+with)\s+(.+?)\s*\.?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val MARK_DONE_REGEX = Regex(
        """^\s*mark\s+(.+?)\s+(?:as\s+)?(?:done|complete|finished|completed)\s*\.?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val UNCOMPLETE_VERB_REGEX = Regex(
        """^\s*(?:reopen|undo|uncomplete|unfinish)\s+(.+?)\s*\.?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val MARK_NOT_DONE_REGEX = Regex(
        """^\s*mark\s+(.+?)\s+(?:as\s+)?not\s+(?:done|complete|finished|completed)\s*\.?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseComplete(msg: String): TodoCommand? {
        // Mark-not-done MUST be tried before mark-done so "mark #2 as not
        // done" doesn't get caught by the broader done verb.
        if (MARK_NOT_DONE_REGEX.matches(msg)) return null
        val match = COMPLETE_VERB_REGEX.matchEntire(msg) ?: MARK_DONE_REGEX.matchEntire(msg) ?: return null
        val ref = parseRef(match.groupValues[1]) ?: return null
        return TodoCommand.SetCompleted(ref, completed = true)
    }

    private fun parseUncomplete(msg: String): TodoCommand? {
        val match = MARK_NOT_DONE_REGEX.matchEntire(msg) ?: UNCOMPLETE_VERB_REGEX.matchEntire(msg) ?: return null
        val ref = parseRef(match.groupValues[1]) ?: return null
        return TodoCommand.SetCompleted(ref, completed = false)
    }

    // ---- set priority -------------------------------------------------------

    private val SET_PRIORITY_REGEX = Regex(
        """^\s*(?:set|change|make|mark)\s+(.+?)\s+(?:to|as)\s+(?:a\s+)?(low|medium|med|normal|high|urgent)(?:\s+priority)?\s*\.?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    // "make todo 1 low priority" — same verb set as SET_PRIORITY_REGEX but
    // without the "to/as" bridge. Disambiguated from generic verb commands
    // by the trailing "priority" word.
    private val SET_PRIORITY_NO_BRIDGE_REGEX = Regex(
        """^\s*(?:set|change|make|mark)\s+(.+?)\s+(low|medium|med|normal|high|urgent)\s+priority\s*\.?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val PRIORITY_OF_REGEX = Regex(
        """^\s*(?:set|change)\s+(?:the\s+)?priority\s+of\s+(.+?)\s+(?:to|as)\s+(low|medium|med|normal|high|urgent)\s*\.?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseSetPriority(msg: String): TodoCommand? {
        val match = PRIORITY_OF_REGEX.matchEntire(msg)
            ?: SET_PRIORITY_REGEX.matchEntire(msg)
            ?: SET_PRIORITY_NO_BRIDGE_REGEX.matchEntire(msg)
            ?: return null
        val ref = parseRef(match.groupValues[1]) ?: return null
        val priority = parsePriorityToken(match.groupValues[2]) ?: return null
        return TodoCommand.SetPriority(ref, priority)
    }

    // ---- set due date -------------------------------------------------------

    private val SET_DUE_REGEX = Regex(
        """^\s*(?:set|change)\s+(?:the\s+)?(?:due\s+date|deadline|when)\s+(?:of|for)\s+(.+?)\s+(?:to|for)\s+(today|tomorrow|\d{4}-\d{2}-\d{2})\s*\.?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val MOVE_DUE_REGEX = Regex(
        """^\s*(?:move|reschedule|push|delay)\s+(.+?)\s+(?:to|for|until)\s+(today|tomorrow|\d{4}-\d{2}-\d{2})\s*\.?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseSetDueDate(msg: String): TodoCommand? {
        val match = SET_DUE_REGEX.matchEntire(msg) ?: MOVE_DUE_REGEX.matchEntire(msg) ?: return null
        val ref = parseRef(match.groupValues[1]) ?: return null
        val epochMs = parseAbsoluteDate(match.groupValues[2]) ?: return null
        return TodoCommand.SetDueDate(ref, epochMs)
    }

    // ---- rename -------------------------------------------------------------

    private val RENAME_REGEX = Regex(
        """^\s*(?:rename|retitle|change\s+(?:the\s+)?title\s+of)\s+(.+?)\s+to\s+["']?(.+?)["']?\s*\.?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseRename(msg: String): TodoCommand? {
        val match = RENAME_REGEX.matchEntire(msg) ?: return null
        val ref = parseRef(match.groupValues[1]) ?: return null
        val newTitle = match.groupValues[2].trim().trim('"', '\'')
        if (newTitle.isBlank()) return null
        return TodoCommand.SetTitle(ref, newTitle)
    }

    // ---- delete -------------------------------------------------------------

    private val DELETE_REGEX = Regex(
        """^\s*(?:delete|remove|drop|throw\s+(?:out|away))\s+(.+?)\s*\.?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseDelete(msg: String): TodoCommand? {
        val match = DELETE_REGEX.matchEntire(msg) ?: return null
        val ref = parseRef(match.groupValues[1]) ?: return null
        return TodoCommand.Delete(ref)
    }

    // ---- add ---------------------------------------------------------------

    private val ADD_ANCHOR_REGEX = Regex(
        """^\s*(?:add|create|new|remember\s+to|i\s+need\s+to|don'?t\s+let\s+me\s+forget(?:\s+to)?)\s+""",
        RegexOption.IGNORE_CASE,
    )
    private val ADD_LIST_SUFFIX_REGEX = Regex(
        """\s+to\s+(?:my\s+)?(?:todos?|to[- ]do(?:\s+list)?|tasks?|task\s+list|checklist|list)\s*\.?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    // Matches a leading "a high priority todo:" / "a new task " wrapper.
    // Captures the priority token (group 1) so the outer parser can hoist
    // it into TodoCommand.Add.priority — otherwise "add a high priority
    // todo: ship PR" would silently lose the priority during the strip.
    private val ADD_NOUN_PREFIX_REGEX = Regex(
        """^(?:a\s+|an\s+|the\s+)?(?:new\s+)?(?:(high|medium|low|urgent)[- ]?(?:priority\s+)?)?(?:todo|task|item)[: ]+""",
        RegexOption.IGNORE_CASE,
    )
    private val ADD_PRIORITY_TRAILING_REGEX = Regex(
        """\s+(?:with\s+)?(low|medium|med|normal|high|urgent)\s+priority\b""",
        RegexOption.IGNORE_CASE,
    )
    private val ADD_PRIORITY_LEADING_REGEX = Regex(
        """^(low|medium|med|normal|high|urgent)\s+priority\s+""",
        RegexOption.IGNORE_CASE,
    )
    private val ADD_DUE_REGEX = Regex(
        """\s+(?:by|due|on|for)\s+(today|tomorrow|\d{4}-\d{2}-\d{2})\s*\.?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseAdd(msg: String): TodoCommand? {
        val anchor = ADD_ANCHOR_REGEX.find(msg) ?: return null
        var body = msg.substring(anchor.range.last + 1)
        body = ADD_LIST_SUFFIX_REGEX.replace(body, "").trim()

        // Noun-prefix strip with priority hoist. If the user wrote "a high
        // priority todo: ship PR" the wrapper's priority token gets pulled
        // out before we drop the rest of the wrapper.
        var priority: TodoPriority? = null
        val nounPrefix = ADD_NOUN_PREFIX_REGEX.find(body)
        if (nounPrefix != null) {
            val token = nounPrefix.groupValues[1].takeIf { it.isNotBlank() }
            if (token != null) priority = parsePriorityToken(token)
            body = body.removeRange(nounPrefix.range).trim()
        }
        if (body.isBlank()) return null

        // Priority — trailing "with high priority" wins over leading
        // "high priority" so a body like "high priority shopping with low
        // priority" resolves to LOW (the more recent qualifier). Both win
        // over a priority hoisted from the noun-prefix wrapper.
        val trailingPriority = ADD_PRIORITY_TRAILING_REGEX.find(body)
        if (trailingPriority != null) {
            priority = parsePriorityToken(trailingPriority.groupValues[1])
            body = body.removeRange(trailingPriority.range).trim()
        } else {
            val leadingPriority = ADD_PRIORITY_LEADING_REGEX.find(body)
            if (leadingPriority != null) {
                priority = parsePriorityToken(leadingPriority.groupValues[1])
                body = body.removeRange(leadingPriority.range).trim()
            }
        }

        // Due date — must come AFTER priority extraction so "priority by
        // tomorrow" doesn't strip the priority word into the date prefix.
        var dueDateEpochMs: Long? = null
        val dueMatch = ADD_DUE_REGEX.find(body)
        if (dueMatch != null) {
            val parsed = parseAbsoluteDate(dueMatch.groupValues[1])
            if (parsed != null) {
                dueDateEpochMs = parsed
                body = body.removeRange(dueMatch.range).trim()
            }
        }

        val title = body.trimEnd('.', '!', '?', ',').trim()
        if (title.isBlank()) return null
        return TodoCommand.Add(title = title, priority = priority, dueDateEpochMs = dueDateEpochMs)
    }

    // ---- shared helpers -----------------------------------------------------

    /**
     * Parse the trailing portion of a verb-anchored command into either a
     * 1-based index reference or a title substring. Returns null when the
     * input is blank — in which case the caller treats the whole command
     * as unmatched and we'll emit guidance rather than guess.
     */
    internal fun parseRef(text: String): TodoRef? {
        val trimmed = text.trim().trimEnd('.', '!', '?', ',').trim()
        if (trimmed.isBlank()) return null
        Regex("""^#?(\d+)$""").matchEntire(trimmed)?.let { m ->
            m.groupValues[1].toIntOrNull()?.takeIf { it > 0 }?.let { return TodoRef.Index(it) }
        }
        // Strip leading "the/my/a/an" and any "todo"/"task"/"item" wrappers
        // so "the gym task" → "gym".
        val cleaned = trimmed
            .replace(Regex("""^(?:the\s+|my\s+|a\s+|an\s+)+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(?:todo|task|item)$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(?:todo|task|item)\s+""", RegexOption.IGNORE_CASE), "")
            .trim()
        if (cleaned.isBlank()) return null
        Regex("""^#?(\d+)$""").matchEntire(cleaned)?.let { m ->
            m.groupValues[1].toIntOrNull()?.takeIf { it > 0 }?.let { return TodoRef.Index(it) }
        }
        return TodoRef.TitleSubstring(cleaned.trim('"', '\''))
    }

    private fun parsePriorityToken(raw: String): TodoPriority? = when (raw.lowercase()) {
        "low" -> TodoPriority.LOW
        "medium", "med", "normal" -> TodoPriority.MEDIUM
        "high", "urgent" -> TodoPriority.HIGH
        else -> null
    }

    /**
     * Resolve a date keyword or ISO-8601 date to epoch ms at midnight in
     * the current system time zone. The narrow grammar is intentional —
     * anything richer (natural language relative dates, "next Tuesday")
     * goes through the partial-match guidance path instead of touching
     * the LLM.
     */
    private fun parseAbsoluteDate(token: String): Long? {
        val tz = timeZoneProvider()
        val today: LocalDate = clock.now().toLocalDateTime(tz).date
        val target: LocalDate = when (token.lowercase()) {
            "today" -> today
            "tomorrow" -> today.plus(1, DateTimeUnit.DAY)
            else -> runCatching { LocalDate.parse(token) }.getOrNull() ?: return null
        }
        return LocalDateTime(target, LocalTime(0, 0)).toInstant(tz).toEpochMilliseconds()
    }

    private companion object {
        val LEADING_SCAFFOLDING_REGEX = Regex(
            """^\s*(?:now|actually|so|and|ok|okay|well|um|er|but|please|hey|can you|could you|would you|will you|i want to|i'd like to|let's)\s*[,\.]?\s*""",
            RegexOption.IGNORE_CASE,
        )
    }
}
