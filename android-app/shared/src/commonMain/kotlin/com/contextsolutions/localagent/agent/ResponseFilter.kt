package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.language.PreferredLanguage

/**
 * Strips characters from the model's streamed output that fall outside the
 * user's preferred-language allow-list. Acts as a safety net behind the
 * system-prompt directive — Gemma 4 E2B occasionally code-switches mid-
 * response (a CJK token slips into top-k for a common English noun), and
 * the prompt-level fix alone doesn't catch every case (see the user's
 * "物体" report).
 *
 * Applied inside [AgentLoop] at two points:
 *   1. Each [GenerationEvent.TokenChunk] is filtered before being appended
 *      to `finalText` and emitted as [AgentEvent.TokenChunk]. The UI sees
 *      clean text.
 *   2. The accumulated `finalText` is therefore implicitly clean when
 *      built into the final [ChatMessage.Assistant], which lands in
 *      `turnMessages` — so the next turn's prompt history doesn't carry
 *      leaked characters that might re-prime the model.
 *
 * **Translation requests use [NoOp].** A turn that asks the model to
 * translate, or that itself contains non-native-script characters, has
 * the filter swapped to [NoOp] by the caller (typically [ChatViewModel]
 * driven by [TranslationIntentDetector]).
 *
 * Stripping is silent — no placeholders, no warnings. Adjacent allowed
 * characters close up around the removal: `"objects 物体 behave"` becomes
 * `"objects  behave"` (double space preserved so word boundaries stay
 * legible).
 */
fun interface ResponseFilter {

    /** Return the input with disallowed characters removed. */
    fun filter(text: String): String

    companion object {
        /** Pass-through filter for translation requests / disabled enforcement. */
        val NoOp: ResponseFilter = ResponseFilter { it }

        /**
         * Build a filter that keeps only characters allowed by [language]
         * (its native-script ranges plus the always-allowed baseline of
         * Latin + math + Greek + common symbols).
         *
         * Whitespace is always preserved — `' '`, `'\n'`, `'\t'` are all
         * inside Basic Latin so the always-allowed baseline already covers
         * them; an explicit guard isn't needed.
         */
        fun allowedScripts(language: PreferredLanguage): ResponseFilter = ResponseFilter { text ->
            if (text.isEmpty()) return@ResponseFilter text
            // Fast path: scan once; if everything's allowed, return the
            // input unchanged so the JVM doesn't allocate a new String.
            var firstBad = -1
            for (i in text.indices) {
                if (!language.isAllowed(text[i].code)) {
                    firstBad = i
                    break
                }
            }
            if (firstBad < 0) return@ResponseFilter text
            // Slow path: copy the kept prefix and append every subsequent
            // allowed character. Stripping is silent — no replacement char.
            buildString(text.length) {
                append(text, 0, firstBad)
                for (i in firstBad + 1 until text.length) {
                    if (language.isAllowed(text[i].code)) append(text[i])
                }
            }
        }
    }
}
