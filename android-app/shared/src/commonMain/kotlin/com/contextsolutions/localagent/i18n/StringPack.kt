package com.contextsolutions.localagent.i18n

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The parsed value behind one catalog key. The JSON shape distinguishes them:
 *  - a string  → [Simple]
 *  - an object → [Plural] (CLDR-category → template)
 *  - an array  → [Listed] (voice/ack phrase lists)
 */
sealed interface StringValue {
    data class Simple(val text: String) : StringValue
    data class Plural(val forms: Map<String, String>) : StringValue
    data class Listed(val items: List<String>) : StringValue
}

/**
 * Immutable, parsed strings for a single language plus its resolved
 * [PluralRule]. Pure data — built either in code ([EnglishStrings], the
 * fallback floor) or from a bundled/remote JSON pack ([parse]). Lookups are
 * typed so a key authored as a plural can't be read as a simple string.
 */
class StringPack(
    val code: String,
    private val values: Map<String, StringValue>,
    val plurals: PluralRule,
) {
    fun simple(key: String): String? = (values[key] as? StringValue.Simple)?.text
    fun pluralForms(key: String): Map<String, String>? = (values[key] as? StringValue.Plural)?.forms
    fun list(key: String): List<String>? = (values[key] as? StringValue.Listed)?.items

    /** Keys present in this pack (excludes the `_meta` block). For guardrail tests. */
    val keys: Set<String> get() = values.keys

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Reserved JSON key carrying pack metadata (`{ "code": ..., "plurals": ... }`). */
        const val META_KEY: String = "_meta"

        /**
         * Parse a JSON pack. [code] is the caller-known language code used when
         * the pack omits `_meta.code`; the pack's own `_meta.plurals` (if any)
         * picks the [PluralRule], otherwise it's derived from [code].
         * Throws on malformed JSON — callers decide whether to swallow (a
         * bundled non-EN pack that fails to parse should fall back to English,
         * not crash; the in-code English floor is never parsed).
         */
        fun parse(jsonText: String, code: String): StringPack {
            val root = json.parseToJsonElement(jsonText).jsonObject
            val meta = root[META_KEY] as? JsonObject
            val resolvedCode = (meta?.get("code") as? JsonPrimitive)?.content ?: code
            val pluralName = (meta?.get("plurals") as? JsonPrimitive)?.content
            val rule = pluralName?.let { PluralRules.byName(it) } ?: PluralRules.forCode(resolvedCode)

            val values = LinkedHashMap<String, StringValue>(root.size)
            for ((key, element) in root) {
                if (key == META_KEY) continue
                values[key] = when (element) {
                    is JsonPrimitive -> StringValue.Simple(element.content)
                    is JsonObject -> StringValue.Plural(
                        element.mapValues { (_, v) -> v.jsonPrimitive.content },
                    )
                    is JsonArray -> StringValue.Listed(element.map { it.jsonPrimitive.content })
                }
            }
            return StringPack(resolvedCode, values, rule)
        }
    }
}
