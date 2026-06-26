package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.mylist.MyListItemPriority
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
 * Deterministic regex parser for unambiguous "My List" commands. Modelled on
 * [ClockCommandParser] — runs in [AgentLoop.run] BEFORE the LLM so we never
 * pay for Gemma's reliability cost (number mangling, mis-categorisation,
 * etc.) on a list turn. On any partial match the caller falls back to a
 * static guidance string; we deliberately do NOT defer to the LLM.
 *
 * **The phrase "my list" is mandatory** (PR #99): [parse] returns null
 * immediately unless the message contains it. Bare "list" is too general
 * and "todo" is gone, so every supported phrasing names the surface
 * explicitly — "add buy milk **to my list**", "show **my list**", "check off
 * milk **on my list**". The verb grammar below runs against the message with
 * the `<preposition> my list` locator peeled off, so the action + target are
 * isolated.
 *
 * Coverage is intentionally narrow:
 *  - Add: `add <title> to my list` / `remember to <title> ... to my list`.
 *  - Show: `my list`, `show my list`, `what's on my list`.
 *  - Complete / uncomplete: `complete #N on my list`, `mark <ref> done on my list`.
 *  - Delete: `remove <title> from my list`.
 *  - Edit: `set #N to high priority on my list`, `move #N to tomorrow on my list`.
 *  - Clear completed: `clear completed from my list`.
 *
 * Due dates accept `today`, `tomorrow`, and ISO `YYYY-MM-DD`. Anything more
 * natural-language than that intentionally falls through to a partial-match
 * guidance reply rather than to Gemma.
 */
class MyListCommandParser(
    private val clock: Clock = Clock.System,
    private val timeZoneProvider: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {

    fun parse(message: String): MyListCommand? {
        val raw = message.trim()
        if (raw.isEmpty()) return null
        // Hard gate: the surface is named "My List" and the trigger phrase is
        // mandatory. Without "my list" this isn't a list command at all.
        if (!MENTIONS_MY_LIST.containsMatchIn(raw)) return null
        val msg = LEADING_SCAFFOLDING_REGEX.replaceFirst(raw, "").ifBlank { raw }

        // Read intents keep "my list" as their object, so they're matched on
        // the full message first. Show-ONE ("show number 2 on my list") is tried
        // before the show-all grammar so a numeric/title ref isn't swallowed.
        parseShowOne(msg)?.let { return it }
        parseShow(msg)?.let { return it }

        // Every other command names the surface as a locator ("... on my
        // list"); peel it off so the verb grammar sees just action + target.
        val core = stripMyListLocator(msg)
        // Order: most-specific first. Clear-completed before delete (which
        // would otherwise eat "delete completed items"). Priority/due-date
        // are anchored on disjoint trailing tokens so order between them
        // doesn't matter, but "add" runs last because it's the most general
        // verb.
        return parseClearCompleted(core)
            ?: parseComplete(core)
            ?: parseUncomplete(core)
            ?: parseSetPriority(core)
            ?: parseSetDueDate(core)
            ?: parseRename(core)
            ?: parseDelete(core)
            ?: parseAdd(core)
    }

    /** Remove a "<preposition> my list" locator (and any bare trailing "my list"). */
    private fun stripMyListLocator(msg: String): String {
        var s = LOCATOR_REGEX.replace(msg, " ")
        s = TRAILING_MY_LIST_REGEX.replace(s, " ")
        return WHITESPACE_REGEX.replace(s, " ").trim()
    }

    // ---- show ---------------------------------------------------------------

    // The literal "my list" stays intact (the gate requires it), so any
    // "completed/all" qualifier rides BEFORE "my" ("show all of my list") or
    // AFTER "list" ("show my list including completed") — never between, which
    // would break the "my list" adjacency the gate enforces.
    private val SHOW_REGEX = Regex(
        """^\s*(?:(?:please|can\s+you|could\s+you)\s+)?(?:(?:show|read|display|view|see|give|get|pull\s+up|bring\s+up|open|tell|what(?:'?s|\s+is|\s+are)?|whats|which|how\s+many|do\s+i\s+have)\b[^.!?\n]*?\s+)?(?:on|in|of)?\s*(?:all\s+(?:of\s+)?|everything\s+(?:on|in)\s+)?my\s+lists?(?:\s+items?)?(?:\s+(?:please|now|including\s+completed|with\s+completed|do\s+i\s+have|are\s+(?:left|open|done|completed)))?\s*\??\s*\.?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    // Show a single item: "show number 2 on my list", "show #2 on my list",
    // "show the milk item on my list". Group 1 = the ref text BEFORE the
    // "<preposition> my list" locator. Requires that locator (so it never
    // hijacks plain "show my list", which has no preposition before "my").
    private val SHOW_ONE_REGEX = Regex(
        """^\s*(?:(?:please|can\s+you|could\s+you)\s+)?(?:show|read|display|view|see|give|get|pull\s+up|bring\s+up|open|tell|what(?:'?s|\s+is|\s+are)?|whats|which)\s+(?:me\s+)?(.+?)\s+(?:on|in|of|from)\s+my\s+lists?\s*\??\s*\.?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    // Qualifier-only refs ("all", "everything", "completed", …) are show-ALL,
    // not show-one — let [parseShow] handle them.
    private val SHOW_ALL_QUALIFIER_REGEX = Regex(
        """^(?:all|everything|whole|entire|full|completed|finished|done|open|active|remaining)\b.*$""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseShowOne(msg: String): MyListCommand? {
        val match = SHOW_ONE_REGEX.matchEntire(msg) ?: return null
        val refText = match.groupValues[1].trim()
        if (refText.isBlank() || SHOW_ALL_QUALIFIER_REGEX.matches(refText)) return null
        val ref = parseRef(refText) ?: return null
        return MyListCommand.Show(includeCompleted = false, ref = ref)
    }

    private fun parseShow(msg: String): MyListCommand? {
        if (!SHOW_REGEX.matches(msg)) return null
        val lower = msg.lowercase()
        val includeCompleted = lower.contains("all") ||
            lower.contains("complete") ||
            lower.contains("finished") ||
            lower.contains("done") ||
            lower.contains("everything") ||
            lower.contains("whole") ||
            lower.contains("entire") ||
            lower.contains("full")
        return MyListCommand.Show(includeCompleted = includeCompleted)
    }

    // ---- clear completed ----------------------------------------------------

    private val CLEAR_COMPLETED_REGEX = Regex(
        """^\s*(?:clear|delete|remove|wipe)\s+(?:all\s+)?(?:my\s+|the\s+)?(?:completed|finished|done|checked[- ]off)(?:\s+(?:items?|tasks?|things?|entries))?\s*\.?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseClearCompleted(msg: String): MyListCommand? =
        if (CLEAR_COMPLETED_REGEX.matches(msg)) MyListCommand.ClearCompleted else null

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

    private fun parseComplete(msg: String): MyListCommand? {
        // Mark-not-done MUST be tried before mark-done so "mark #2 as not
        // done" doesn't get caught by the broader done verb.
        if (MARK_NOT_DONE_REGEX.matches(msg)) return null
        val match = COMPLETE_VERB_REGEX.matchEntire(msg) ?: MARK_DONE_REGEX.matchEntire(msg) ?: return null
        val ref = parseRef(match.groupValues[1]) ?: return null
        return MyListCommand.SetCompleted(ref, completed = true)
    }

    private fun parseUncomplete(msg: String): MyListCommand? {
        val match = MARK_NOT_DONE_REGEX.matchEntire(msg) ?: UNCOMPLETE_VERB_REGEX.matchEntire(msg) ?: return null
        val ref = parseRef(match.groupValues[1]) ?: return null
        return MyListCommand.SetCompleted(ref, completed = false)
    }

    // ---- set priority -------------------------------------------------------

    private val SET_PRIORITY_REGEX = Regex(
        """^\s*(?:set|change|make|mark)\s+(.+?)\s+(?:to|as)\s+(?:a\s+)?(low|medium|med|normal|high|urgent)(?:\s+priority)?\s*\.?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    // "make item 1 low priority" — same verb set as SET_PRIORITY_REGEX but
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

    private fun parseSetPriority(msg: String): MyListCommand? {
        val match = PRIORITY_OF_REGEX.matchEntire(msg)
            ?: SET_PRIORITY_REGEX.matchEntire(msg)
            ?: SET_PRIORITY_NO_BRIDGE_REGEX.matchEntire(msg)
            ?: return null
        val ref = parseRef(match.groupValues[1]) ?: return null
        val priority = parsePriorityToken(match.groupValues[2]) ?: return null
        return MyListCommand.SetPriority(ref, priority)
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

    private fun parseSetDueDate(msg: String): MyListCommand? {
        val match = SET_DUE_REGEX.matchEntire(msg) ?: MOVE_DUE_REGEX.matchEntire(msg) ?: return null
        val ref = parseRef(match.groupValues[1]) ?: return null
        val epochMs = parseAbsoluteDate(match.groupValues[2]) ?: return null
        return MyListCommand.SetDueDate(ref, epochMs)
    }

    // ---- rename -------------------------------------------------------------

    private val RENAME_REGEX = Regex(
        """^\s*(?:rename|retitle|change\s+(?:the\s+)?title\s+of)\s+(.+?)\s+to\s+["']?(.+?)["']?\s*\.?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseRename(msg: String): MyListCommand? {
        val match = RENAME_REGEX.matchEntire(msg) ?: return null
        val ref = parseRef(match.groupValues[1]) ?: return null
        val newTitle = match.groupValues[2].trim().trim('"', '\'')
        if (newTitle.isBlank()) return null
        return MyListCommand.SetTitle(ref, newTitle)
    }

    // ---- delete -------------------------------------------------------------

    private val DELETE_REGEX = Regex(
        """^\s*(?:delete|remove|drop|throw\s+(?:out|away))\s+(.+?)\s*\.?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseDelete(msg: String): MyListCommand? {
        val match = DELETE_REGEX.matchEntire(msg) ?: return null
        val ref = parseRef(match.groupValues[1]) ?: return null
        return MyListCommand.Delete(ref)
    }

    // ---- add ---------------------------------------------------------------

    private val ADD_ANCHOR_REGEX = Regex(
        """^\s*(?:add|create|new|remember\s+to|i\s+need\s+to|don'?t\s+let\s+me\s+forget(?:\s+to)?)\s+""",
        RegexOption.IGNORE_CASE,
    )
    // A leftover bare "list"/"task list" suffix after the locator strip — e.g.
    // "add milk to the list" (when "my list" appeared elsewhere) leaves a
    // trailing "to the list". Harmless to trim.
    private val ADD_LIST_SUFFIX_REGEX = Regex(
        """\s+to\s+(?:the\s+|my\s+)?(?:task\s+)?lists?\s*\.?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    // Matches a leading "a high priority item:" / "a new task " wrapper.
    // Captures the priority token (group 1) so the outer parser can hoist
    // it into MyListCommand.Add.priority — otherwise "add a high priority
    // item: ship PR" would silently lose the priority during the strip.
    private val ADD_NOUN_PREFIX_REGEX = Regex(
        """^(?:a\s+|an\s+|the\s+)?(?:new\s+)?(?:(high|medium|low|urgent)[- ]?(?:priority\s+)?)?(?:item|task|entry)[: ]+""",
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

    private fun parseAdd(msg: String): MyListCommand? {
        val anchor = ADD_ANCHOR_REGEX.find(msg) ?: return null
        var body = msg.substring(anchor.range.last + 1)
        body = ADD_LIST_SUFFIX_REGEX.replace(body, "").trim()

        // Noun-prefix strip with priority hoist. If the user wrote "a high
        // priority item: ship PR" the wrapper's priority token gets pulled
        // out before we drop the rest of the wrapper.
        var priority: MyListItemPriority? = null
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
        return MyListCommand.Add(title = title, priority = priority, dueDateEpochMs = dueDateEpochMs)
    }

    // ---- shared helpers -----------------------------------------------------

    /**
     * Parse the trailing portion of a verb-anchored command into either a
     * 1-based index reference or a title substring. Returns null when the
     * input is blank — in which case the caller treats the whole command
     * as unmatched and we'll emit guidance rather than guess.
     */
    internal fun parseRef(text: String): MyListRef? {
        val trimmed = text.trim().trimEnd('.', '!', '?', ',').trim()
        if (trimmed.isBlank()) return null
        Regex("""^#?(\d+)$""").matchEntire(trimmed)?.let { m ->
            m.groupValues[1].toIntOrNull()?.takeIf { it > 0 }?.let { return MyListRef.Index(it) }
        }
        // Strip leading "the/my/a/an" and any "item"/"task"/"entry" wrappers
        // so "the gym item" → "gym".
        val cleaned = trimmed
            .replace(Regex("""^(?:the\s+|my\s+|a\s+|an\s+)+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(?:item|task|entry)$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(?:item|task|entry)\s+""", RegexOption.IGNORE_CASE), "")
            // "number 2" / "no. 2" / "num 2" → "2" so it resolves as an index.
            .replace(Regex("""^(?:number|no\.?|num)\s+""", RegexOption.IGNORE_CASE), "")
            .trim()
        if (cleaned.isBlank()) return null
        Regex("""^#?(\d+)$""").matchEntire(cleaned)?.let { m ->
            m.groupValues[1].toIntOrNull()?.takeIf { it > 0 }?.let { return MyListRef.Index(it) }
        }
        // Spelled-out numbers ("number two" → 2). Only when the whole token is a
        // number word, so a title like "two birds" stays a title match.
        wordToNumber(cleaned)?.let { return MyListRef.Index(it) }
        return MyListRef.TitleSubstring(cleaned.trim('"', '\''))
    }

    /**
     * Convert an English number word (or hyphen/space compound, 1–99) to its
     * value. Returns null unless the ENTIRE input is a valid positive number
     * phrase — "two" → 2, "twenty one" → 21, "two birds" / "zero" → null.
     */
    private fun wordToNumber(text: String): Int? {
        val s = text.trim().lowercase().replace('-', ' ').replace(WHITESPACE_REGEX, " ")
        if (s.isBlank()) return null
        UNITS[s]?.let { return it.takeIf { v -> v > 0 } }
        TENS[s]?.let { return it }
        val parts = s.split(' ')
        if (parts.size == 2) {
            val tens = TENS[parts[0]]
            val unit = UNITS[parts[1]]
            if (tens != null && unit != null && unit in 1..9) return tens + unit
        }
        return null
    }

    private fun parsePriorityToken(raw: String): MyListItemPriority? = when (raw.lowercase()) {
        "low" -> MyListItemPriority.LOW
        "medium", "med", "normal" -> MyListItemPriority.MEDIUM
        "high", "urgent" -> MyListItemPriority.HIGH
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
        val MENTIONS_MY_LIST = Regex("""\bmy\s+lists?\b""", RegexOption.IGNORE_CASE)
        val LOCATOR_REGEX = Regex(
            """\s*\b(?:to|from|on|onto|in|into|off(?:\s+of)?|out\s+of)\s+my\s+lists?\b""",
            RegexOption.IGNORE_CASE,
        )
        val TRAILING_MY_LIST_REGEX = Regex("""\s*\bmy\s+lists?\b\s*$""", RegexOption.IGNORE_CASE)
        val WHITESPACE_REGEX = Regex("""\s+""")
        // Number words for "show number two on my list" (0–19 + tens; compounds
        // like "twenty one" are summed in wordToNumber).
        val UNITS: Map<String, Int> = mapOf(
            "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
            "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
            "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
            "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
            "eighteen" to 18, "nineteen" to 19,
        )
        val TENS: Map<String, Int> = mapOf(
            "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
            "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
        )
        val LEADING_SCAFFOLDING_REGEX = Regex(
            """^\s*(?:now|actually|so|and|ok|okay|well|um|er|but|please|hey|can you|could you|would you|will you|i want to|i'd like to|let's)\s*[,\.]?\s*""",
            RegexOption.IGNORE_CASE,
        )
    }
}
