package com.contextsolutions.localagent.i18n

/**
 * The accessor every consumer calls. Wraps the [active] language pack over the
 * always-present English [fallback] floor ([EnglishStrings]); any key the
 * active pack is missing resolves against the fallback, so a partial
 * translation degrades gracefully to English instead of showing a raw key.
 *
 * Resolve one [Strings] per turn (the agent layer) or per language flip (the
 * Compose layer) — never per token. Lookups are cheap map reads, but the
 * positional formatter allocates, so keep them off the streaming hot path.
 */
class Strings(
    val active: StringPack,
    val fallback: StringPack,
) {
    /** Simple string by [key], with positional args (`%1$s`, `%2$d`, `%%`). */
    fun get(key: String, vararg args: Any?): String {
        val template = active.simple(key) ?: fallback.simple(key) ?: return missing(key)
        return formatPositional(template, args)
    }

    /**
     * Plural string: picks the CLDR category for [count] using the pack that
     * actually carries the key (so the category matches the language whose text
     * is rendered), then formats with [args]. Falls back to `"other"`, then to
     * the English floor.
     */
    fun plural(key: String, count: Int, vararg args: Any?): String {
        val (forms, rule) = when {
            active.pluralForms(key) != null -> active.pluralForms(key)!! to active.plurals
            fallback.pluralForms(key) != null -> fallback.pluralForms(key)!! to fallback.plurals
            else -> return missing(key)
        }
        val category = rule.categoryFor(count)
        val template = forms[category] ?: forms["other"]
            ?: fallback.pluralForms(key)?.get("other")
            ?: return missing(key)
        return formatPositional(template, args)
    }

    /** Phrase list by [key] (voice commands / ack phrases). Empty if absent. */
    fun list(key: String): List<String> = active.list(key) ?: fallback.list(key) ?: emptyList()

    /**
     * A missing key never crashes. We return the key itself so the surface
     * stays usable and the gap is visible in logs/screenshots. The guardrail
     * test makes English misses impossible (the floor is in-code and complete),
     * so this only fires for an unkeyed string or a typo'd lookup.
     */
    private fun missing(key: String): String = key

    companion object {
        /** English-over-English — the default for tests and any caller without a catalog. */
        val ENGLISH: Strings by lazy { Strings(EnglishStrings.pack, EnglishStrings.pack) }
    }
}
