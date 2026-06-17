package com.contextsolutions.localagent.language

/**
 * The user's preferred response language. Drives two things:
 *  1. The system-prompt directive ("Respond in {English} unless the user asks
 *     for a translation or another language").
 *  2. The [com.contextsolutions.localagent.agent.ResponseFilter] Unicode
 *     allow-list — Gemma 4 E2B occasionally leaks foreign-script tokens into
 *     responses (the original bug behind this PR), so the streamed output is
 *     filtered against the allow-list as a safety net.
 *
 * **Allow-list policy.** Every language allows the [ALWAYS_ALLOWED_RANGES]
 * baseline — Latin (basic + extended + Latin-1 supplement), digits, math,
 * Greek, common symbols. That's enough for English-style content in *any*
 * native-language setting. The language then layers its own native-script
 * ranges on top via [extraAllowedRanges]. So a Chinese-default user gets
 * Latin + CJK; a Japanese-default user gets Latin + CJK + Hiragana +
 * Katakana; etc.
 *
 * **What's blocked by default.** Scripts the user almost certainly didn't
 * ask for: when native=English, CJK / Cyrillic / Arabic / Hangul / Hiragana
 * / Katakana / Hebrew / Devanagari / Thai etc. all get stripped. The
 * [com.contextsolutions.localagent.agent.TranslationIntentDetector] flips
 * the filter OFF for the turn whenever the user actually asks for cross-
 * language content.
 *
 * v1 ships 10 curated languages; the table can grow without breaking
 * existing persisted preferences (deserialise falls back to [DEFAULT] for
 * unknown codes).
 */
enum class PreferredLanguage(
    /** ISO 639-1 code. Stable wire identifier — persisted in SharedPreferences. */
    val code: String,
    /** English display name shown in Settings. */
    val englishName: String,
    /** Native-script display name shown in Settings (e.g. "中文" for zh). */
    val nativeName: String,
    /**
     * Unicode ranges layered ON TOP of [ALWAYS_ALLOWED_RANGES]. These are the
     * blocks unique to this language's primary script(s); the always-allowed
     * baseline covers Latin + math + Greek + punctuation for everyone.
     */
    val extraAllowedRanges: List<IntRange>,
) {
    EN(code = "en", englishName = "English", nativeName = "English", extraAllowedRanges = emptyList()),
    ES(code = "es", englishName = "Spanish", nativeName = "Español", extraAllowedRanges = emptyList()),
    FR(code = "fr", englishName = "French", nativeName = "Français", extraAllowedRanges = emptyList()),
    DE(code = "de", englishName = "German", nativeName = "Deutsch", extraAllowedRanges = emptyList()),
    IT(code = "it", englishName = "Italian", nativeName = "Italiano", extraAllowedRanges = emptyList()),
    PT(code = "pt", englishName = "Portuguese", nativeName = "Português", extraAllowedRanges = emptyList()),
    ZH(
        code = "zh",
        englishName = "Chinese (Simplified)",
        nativeName = "中文",
        extraAllowedRanges = listOf(
            UnicodeRanges.CJK_UNIFIED_IDEOGRAPHS,
            UnicodeRanges.CJK_SYMBOLS_AND_PUNCTUATION,
            UnicodeRanges.HALFWIDTH_FULLWIDTH_FORMS,
        ),
    ),
    JA(
        code = "ja",
        englishName = "Japanese",
        nativeName = "日本語",
        extraAllowedRanges = listOf(
            UnicodeRanges.CJK_UNIFIED_IDEOGRAPHS,
            UnicodeRanges.CJK_SYMBOLS_AND_PUNCTUATION,
            UnicodeRanges.HIRAGANA,
            UnicodeRanges.KATAKANA,
            UnicodeRanges.HALFWIDTH_FULLWIDTH_FORMS,
        ),
    ),
    KO(
        code = "ko",
        englishName = "Korean",
        nativeName = "한국어",
        extraAllowedRanges = listOf(
            UnicodeRanges.HANGUL_SYLLABLES,
            UnicodeRanges.HANGUL_JAMO,
            UnicodeRanges.HANGUL_COMPATIBILITY_JAMO,
            UnicodeRanges.CJK_UNIFIED_IDEOGRAPHS, // hanja loans
            UnicodeRanges.CJK_SYMBOLS_AND_PUNCTUATION,
        ),
    ),
    RU(
        code = "ru",
        englishName = "Russian",
        nativeName = "Русский",
        extraAllowedRanges = listOf(
            UnicodeRanges.CYRILLIC,
            UnicodeRanges.CYRILLIC_SUPPLEMENT,
        ),
    );

    /**
     * Test a single code point against this language's allow-list. Returns
     * `true` for any code point covered by [ALWAYS_ALLOWED_RANGES] or by
     * this language's [extraAllowedRanges].
     */
    fun isAllowed(codePoint: Int): Boolean {
        if (UnicodeRanges.ALWAYS_ALLOWED.any { codePoint in it }) return true
        return extraAllowedRanges.any { codePoint in it }
    }

    companion object {
        /** Phase 1 default for fresh installs. */
        val DEFAULT: PreferredLanguage = EN

        /**
         * Languages offered in the UI picker (onboarding + Settings).
         * English-only at launch (PR #99). The full [entries] table, string
         * packs, and catalog all stay in place — add entries here to re-enable
         * another language without touching the picker composables.
         */
        val selectable: List<PreferredLanguage> = listOf(EN)

        /**
         * Resolve a persisted ISO code back to an enum value. Unknown / null
         * codes return [DEFAULT] so a future re-export that drops a language
         * doesn't crash existing installs.
         */
        fun fromCode(code: String?): PreferredLanguage =
            values().firstOrNull { it.code == code } ?: DEFAULT
    }
}

/**
 * Unicode block ranges referenced by [PreferredLanguage] entries. Held at
 * file level (not inside the enum's companion) because enum entries can't
 * reference the companion during their own initialisation — Kotlin loads
 * the entries first.
 *
 * Codes are the official Unicode block boundaries; see
 * https://www.unicode.org/Public/UCD/latest/ucd/Blocks.txt for the
 * authoritative table.
 */
internal object UnicodeRanges {

    // ─── Always allowed regardless of native language ────────────────────
    // Every assistant response uses ASCII letters/digits/punctuation, math
    // operators, common symbols. Greek covers science/math (Δ, π, σ);
    // Latin extensions cover café-style accents even in English content.
    val BASIC_LATIN: IntRange = 0x0000..0x007F
    val LATIN_1_SUPPLEMENT: IntRange = 0x0080..0x00FF
    val LATIN_EXTENDED_A: IntRange = 0x0100..0x017F
    val LATIN_EXTENDED_B: IntRange = 0x0180..0x024F
    val IPA_EXTENSIONS: IntRange = 0x0250..0x02AF
    val SPACING_MODIFIER_LETTERS: IntRange = 0x02B0..0x02FF
    val COMBINING_DIACRITICAL_MARKS: IntRange = 0x0300..0x036F
    val GREEK_AND_COPTIC: IntRange = 0x0370..0x03FF
    val GENERAL_PUNCTUATION: IntRange = 0x2000..0x206F
    val SUPERSCRIPTS_AND_SUBSCRIPTS: IntRange = 0x2070..0x209F
    val CURRENCY_SYMBOLS: IntRange = 0x20A0..0x20CF
    val LETTERLIKE_SYMBOLS: IntRange = 0x2100..0x214F
    val NUMBER_FORMS: IntRange = 0x2150..0x218F
    val ARROWS: IntRange = 0x2190..0x21FF
    val MATHEMATICAL_OPERATORS: IntRange = 0x2200..0x22FF
    val MISCELLANEOUS_TECHNICAL: IntRange = 0x2300..0x23FF
    val GEOMETRIC_SHAPES: IntRange = 0x25A0..0x25FF
    val MISCELLANEOUS_SYMBOLS: IntRange = 0x2600..0x26FF

    val ALWAYS_ALLOWED: List<IntRange> = listOf(
        BASIC_LATIN,
        LATIN_1_SUPPLEMENT,
        LATIN_EXTENDED_A,
        LATIN_EXTENDED_B,
        IPA_EXTENSIONS,
        SPACING_MODIFIER_LETTERS,
        COMBINING_DIACRITICAL_MARKS,
        GREEK_AND_COPTIC,
        GENERAL_PUNCTUATION,
        SUPERSCRIPTS_AND_SUBSCRIPTS,
        CURRENCY_SYMBOLS,
        LETTERLIKE_SYMBOLS,
        NUMBER_FORMS,
        ARROWS,
        MATHEMATICAL_OPERATORS,
        MISCELLANEOUS_TECHNICAL,
        GEOMETRIC_SHAPES,
        MISCELLANEOUS_SYMBOLS,
    )

    // ─── Per-language extras ─────────────────────────────────────────────
    val CYRILLIC: IntRange = 0x0400..0x04FF
    val CYRILLIC_SUPPLEMENT: IntRange = 0x0500..0x052F
    val HANGUL_JAMO: IntRange = 0x1100..0x11FF
    val CJK_SYMBOLS_AND_PUNCTUATION: IntRange = 0x3000..0x303F
    val HIRAGANA: IntRange = 0x3040..0x309F
    val KATAKANA: IntRange = 0x30A0..0x30FF
    val HANGUL_COMPATIBILITY_JAMO: IntRange = 0x3130..0x318F
    val CJK_UNIFIED_IDEOGRAPHS: IntRange = 0x4E00..0x9FFF
    val HANGUL_SYLLABLES: IntRange = 0xAC00..0xD7AF
    val HALFWIDTH_FULLWIDTH_FORMS: IntRange = 0xFF00..0xFFEF
}
