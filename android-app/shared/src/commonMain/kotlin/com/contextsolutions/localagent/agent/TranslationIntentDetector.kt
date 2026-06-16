package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.language.PreferredLanguage

/**
 * Decides whether a given user turn is a translation request — in which
 * case the per-turn [ResponseFilter] is swapped to [ResponseFilter.NoOp]
 * so the model can emit characters outside the user's native script.
 *
 * v1 is purely heuristic. False positives (filter relaxed when it shouldn't
 * be) just leave a residual CJK token visible. False negatives (filter
 * enforced when the user wanted a translation) are more user-visible —
 * the requested foreign script gets stripped. So the heuristic is tuned
 * toward false positives.
 *
 * Triggers (any one fires):
 *   1. Verb forms — `translate`, `translation`, `translator`, `translated`,
 *      `translates`, `transliterate(d)`, `interpret(ed)` (case-insensitive).
 *   2. Phrase patterns — "how do you say", "what does (this|that|it)
 *      (mean|say)", "what's the X for", "meaning of", "pronunciation of".
 *   3. Language preposition — "in/to/from/into {languageName}" where
 *      languageName is one of our known language list (English, Spanish,
 *      French, German, Italian, Portuguese, Russian, Chinese, Japanese,
 *      Korean, Mandarin, Cantonese, Arabic, Hindi).
 *   4. The user's own message already contains non-native-script characters
 *      (they're working in a foreign script and almost certainly want a
 *      response that includes it).
 *
 * A v1.x improvement could add a head to the pre-flight classifier with
 * a small training set — but at the cost of dataset work, vs. these
 * regex triggers which cover the common phrasing for free.
 */
class TranslationIntentDetector {

    fun isTranslationRequest(userText: String, native: PreferredLanguage): Boolean {
        if (userText.isBlank()) return false
        val lower = userText.lowercase()

        if (TRANSLATE_VERB_REGEX.containsMatchIn(lower)) return true
        if (HOW_DO_YOU_SAY_REGEX.containsMatchIn(lower)) return true
        if (MEANING_OF_REGEX.containsMatchIn(lower)) return true
        if (LANGUAGE_PREP_REGEX.containsMatchIn(lower)) return true
        if (containsNonNativeScript(userText, native)) return true

        return false
    }

    /**
     * True iff the user's own message contains any code point that is NOT
     * in [native]'s allow-list. That means they're already typing in a
     * foreign script (e.g. native=English, user wrote "what does 你好
     * mean") — relax the filter so the model can echo the script back.
     */
    private fun containsNonNativeScript(text: String, native: PreferredLanguage): Boolean {
        for (ch in text) {
            if (!native.isAllowed(ch.code)) return true
        }
        return false
    }

    private companion object {
        // \btranslate\b, translation(s), translator, translated, translates,
        // transliterate(d), interpret(ed). Word-boundary anchored so we
        // don't trip on "translation" hidden inside an unrelated identifier.
        private val TRANSLATE_VERB_REGEX = Regex(
            """\b(translate(s|d)?|translation(s)?|translator|transliterate(d)?|interpret(ed)?)\b""",
        )

        private val HOW_DO_YOU_SAY_REGEX = Regex(
            """\bhow do (you|i) say\b""",
        )

        private val MEANING_OF_REGEX = Regex(
            """\b(meaning of|pronunciation of|what does (this|that|it) (mean|say)|what's the .+? (for|in)|what is the .+? (for|in))\b""",
        )

        // \bin|to|from|into <languageName>\b — covers all our supported
        // languages plus several common ones a user might mention even
        // if they're not in our enum (Arabic, Hindi, etc.). Listed in
        // their canonical English form; lowercase comparison.
        private val LANGUAGE_NAMES_ALTERNATION = listOf(
            "english", "spanish", "french", "german", "italian", "portuguese",
            "russian", "chinese", "mandarin", "cantonese", "japanese", "korean",
            "arabic", "hindi", "bengali", "punjabi", "tamil", "telugu",
            "urdu", "persian", "farsi", "hebrew", "thai", "vietnamese",
            "indonesian", "malay", "tagalog", "filipino", "turkish", "polish",
            "dutch", "swedish", "norwegian", "danish", "finnish", "greek",
            "czech", "hungarian", "romanian", "ukrainian", "swahili",
        ).joinToString("|")
        private val LANGUAGE_PREP_REGEX = Regex(
            """\b(in|to|from|into) ($LANGUAGE_NAMES_ALTERNATION)\b""",
        )
    }
}
