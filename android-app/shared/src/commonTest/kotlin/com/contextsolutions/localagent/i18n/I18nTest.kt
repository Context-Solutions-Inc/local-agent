package com.contextsolutions.localagent.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class I18nTest {

    // ── Guardrail: the English floor defines exactly the keyed set ──────────

    @Test
    fun english_floor_defines_every_StringKeys_constant() {
        val packKeys = EnglishStrings.pack.keys
        val declared = StringKeys.ALL.toSet()
        val missing = declared - packKeys
        val orphan = packKeys - declared
        assertTrue(missing.isEmpty(), "English floor missing keys: $missing")
        assertTrue(orphan.isEmpty(), "English floor has unkeyed strings: $orphan")
    }

    @Test
    fun StringKeys_ALL_has_no_duplicates() {
        assertEquals(StringKeys.ALL.size, StringKeys.ALL.toSet().size, "duplicate keys in StringKeys.ALL")
    }

    @Test
    fun every_english_plural_defines_other() {
        EnglishStrings.pack.keys.forEach { key ->
            EnglishStrings.pack.pluralForms(key)?.let { forms ->
                assertTrue("other" in forms, "plural '$key' missing the mandatory 'other' form")
            }
        }
    }

    // ── Positional formatter ────────────────────────────────────────────────

    @Test
    fun positional_substitutes_in_order() {
        assertEquals("a then b", formatPositional("%1\$s then %2\$s", arrayOf("a", "b")))
    }

    @Test
    fun positional_handles_reordering_and_repeats() {
        assertEquals("b a b", formatPositional("%2\$s %1\$s %2\$s", arrayOf("a", "b")))
    }

    @Test
    fun positional_d_and_s_both_stringify() {
        assertEquals("5 items", formatPositional("%1\$d items", arrayOf(5)))
    }

    @Test
    fun positional_escapes_double_percent() {
        assertEquals("50% off", formatPositional("%1\$d%% off", arrayOf(50)))
    }

    @Test
    fun positional_emits_out_of_range_specifier_verbatim() {
        assertEquals("x %2\$s", formatPositional("x %2\$s", arrayOf("only-one")))
    }

    // ── Plural rules vs CLDR samples ─────────────────────────────────────────

    @Test
    fun english_plural_rule() {
        val r = PluralRules.ENGLISH
        assertEquals("other", r.categoryFor(0))
        assertEquals("one", r.categoryFor(1))
        assertEquals("other", r.categoryFor(2))
    }

    @Test
    fun french_plural_rule_zero_and_one_are_one() {
        val r = PluralRules.FRENCH
        assertEquals("one", r.categoryFor(0))
        assertEquals("one", r.categoryFor(1))
        assertEquals("other", r.categoryFor(2))
    }

    @Test
    fun russian_plural_rule() {
        val r = PluralRules.RUSSIAN
        assertEquals("one", r.categoryFor(1))
        assertEquals("few", r.categoryFor(2))
        assertEquals("many", r.categoryFor(5))
        assertEquals("one", r.categoryFor(21))
        assertEquals("many", r.categoryFor(11))
        assertEquals("many", r.categoryFor(0))
        assertEquals("few", r.categoryFor(22))
    }

    @Test
    fun cjk_plural_rule_is_other_only() {
        val r = PluralRules.OTHER_ONLY
        assertEquals("other", r.categoryFor(1))
        assertEquals("other", r.categoryFor(5))
    }

    @Test
    fun forCode_maps_languages_to_rules() {
        assertEquals("one", PluralRules.forCode("de").categoryFor(1))   // english-style
        assertEquals("one", PluralRules.forCode("fr").categoryFor(0))   // french
        assertEquals("few", PluralRules.forCode("ru").categoryFor(3))   // russian
        assertEquals("other", PluralRules.forCode("ja").categoryFor(1)) // cjk
    }

    // ── Overlay + fallback (the loader payoff, proven without a bundled pack) ──

    private val d = "${'$'}" // literal '$' for the %n$s specifiers inside raw JSON below

    private val esPackJson = """
        {
          "_meta": { "code": "es", "plurals": "english" },
          "${StringKeys.COMMON_DONE}": "Hecho.",
          "${StringKeys.WEATHER_HEADER}": "Tiempo en %1${d}s",
          "${StringKeys.MYLIST_CLEARED}": { "one": "Se borró %1${d}d tarea.", "other": "Se borraron %1${d}d tareas." }
        }
    """.trimIndent()

    @Test
    fun active_pack_wins_and_missing_keys_fall_back_to_english() {
        val es = StringPack.parse(esPackJson, "es")
        val strings = Strings(active = es, fallback = EnglishStrings.pack)
        // Present in the es pack → translated.
        assertEquals("Hecho.", strings.get(StringKeys.COMMON_DONE))
        assertEquals("Tiempo en Madrid", strings.get(StringKeys.WEATHER_HEADER, "Madrid"))
        // Absent from es → English floor.
        assertEquals("Now: ", strings.get(StringKeys.WEATHER_NOW_PREFIX))
    }

    @Test
    fun plural_uses_active_pack_forms_when_present() {
        val es = StringPack.parse(esPackJson, "es")
        val strings = Strings(active = es, fallback = EnglishStrings.pack)
        assertEquals("Se borró 1 tarea.", strings.plural(StringKeys.MYLIST_CLEARED, 1, 1))
        assertEquals("Se borraron 3 tareas.", strings.plural(StringKeys.MYLIST_CLEARED, 3, 3))
    }

    @Test
    fun plural_falls_back_to_english_when_key_absent_in_active() {
        val es = StringPack.parse(esPackJson, "es")
        val strings = Strings(active = es, fallback = EnglishStrings.pack)
        // CLOCK_CANCELLED_ALARMS isn't in es → English plural + english rule.
        assertEquals("Cancelled 2 alarms.", strings.plural(StringKeys.CLOCK_CANCELLED_ALARMS, 2, 2))
    }

    @Test
    fun missing_key_returns_the_key_never_crashes() {
        val strings = Strings.ENGLISH
        assertEquals("totally.unknown.key", strings.get("totally.unknown.key"))
    }

    @Test
    fun parse_reads_meta_plurals_for_category_selection() {
        val ruJson = """
            {
              "_meta": { "code": "ru", "plurals": "russian" },
              "${StringKeys.MYLIST_CLEARED}": { "one": "%1${d}d-one", "few": "%1${d}d-few", "many": "%1${d}d-many" }
            }
        """.trimIndent()
        val ru = StringPack.parse(ruJson, "ru")
        val strings = Strings(active = ru, fallback = EnglishStrings.pack)
        assertEquals("1-one", strings.plural(StringKeys.MYLIST_CLEARED, 1, 1))
        assertEquals("3-few", strings.plural(StringKeys.MYLIST_CLEARED, 3, 3))
        assertEquals("5-many", strings.plural(StringKeys.MYLIST_CLEARED, 5, 5))
    }
}
