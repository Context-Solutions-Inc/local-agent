package com.contextsolutions.localagent.i18n

/**
 * Maps a count to a CLDR plural category for one language. We don't depend on
 * ICU (it's JVM-only; `:shared` has an `iosMain` set), so the 10 shipped
 * languages collapse to four hand-written rules. `"other"` is mandatory in
 * every pack and is the universal fallback when a rule yields a category a pack
 * doesn't define.
 *
 * Categories used: `"one"`, `"few"`, `"many"`, `"other"` (a subset of CLDR's
 * one/two/few/many/other — none of the 10 languages need `"two"`).
 */
fun interface PluralRule {
    fun categoryFor(count: Int): String
}

object PluralRules {

    /** zh / ja / ko — no grammatical plural; everything is `other`. */
    val OTHER_ONLY: PluralRule = PluralRule { "other" }

    /** en / de / es / it / pt — `one` for exactly 1, else `other`. */
    val ENGLISH: PluralRule = PluralRule { if (it == 1) "one" else "other" }

    /** French — 0 and 1 are `one`, else `other`. */
    val FRENCH: PluralRule = PluralRule { if (it == 0 || it == 1) "one" else "other" }

    /**
     * Russian (CLDR): based on the last digit / last two digits of |count|.
     *  - one: n%10==1 && n%100!=11        (1, 21, 31, 101…)
     *  - few: n%10 in 2..4 && n%100 not in 12..14  (2, 3, 4, 22…)
     *  - many: everything else            (0, 5–20, 11–14, 25…)
     */
    val RUSSIAN: PluralRule = PluralRule { count ->
        val n = if (count < 0) -count else count
        val mod10 = n % 10
        val mod100 = n % 100
        when {
            mod10 == 1 && mod100 != 11 -> "one"
            mod10 in 2..4 && mod100 !in 12..14 -> "few"
            else -> "many"
        }
    }

    /** Resolve the rule for an ISO 639-1 code; unknown codes fall back to [ENGLISH]. */
    fun forCode(code: String): PluralRule = when (code.lowercase()) {
        "zh", "ja", "ko" -> OTHER_ONLY
        "fr" -> FRENCH
        "ru" -> RUSSIAN
        else -> ENGLISH // en, de, es, it, pt, and any future one/other language
    }

    /** Maps the textual `_meta.plurals` field in a JSON pack to a rule. */
    fun byName(name: String?): PluralRule = when (name?.lowercase()) {
        "other", "other_only", "cjk" -> OTHER_ONLY
        "french" -> FRENCH
        "russian" -> RUSSIAN
        else -> ENGLISH // "english", null, or anything unrecognised
    }
}
