package com.contextsolutions.localagent.classifier

/**
 * Pure-Kotlin re-implementation of HuggingFace's
 * [`BertTokenizer`](https://github.com/huggingface/transformers/blob/main/src/transformers/models/bert/tokenization_bert.py)
 * configured for `distilbert-base-uncased`. The on-device classifier (M3 ship,
 * M4 integration) requires byte-exact match with training-time tokenization or
 * the model degrades silently — see `CLASSIFIER_DATASETS.md`, CLAUDE.md hard
 * invariant #13, and `docs/M3_M4_HANDOFF.md` §3.
 *
 * Pipeline (matches HF):
 *
 *   1. **clean_text** — strip control chars, replace any whitespace
 *      character (incl. tab, newline, NBSP, etc.) with ASCII space.
 *   2. **tokenize_chinese_chars** — pad CJK code points with surrounding
 *      spaces so each becomes its own whitespace token.
 *   3. **whitespace_tokenize** — split on any run of whitespace (Python
 *      `str.split()` semantics: drops empty pieces).
 *   4. For each word: lowercase → strip accents (NFD → drop Mn) →
 *      split_on_punc (each punct char becomes its own token).
 *   5. **WordPiece** — greedy longest-match against the vocab; subwords
 *      after the first carry a `##` prefix. Out-of-vocab atoms become `[UNK]`.
 *
 * The fixture test in `:androidApp/src/test/` is the gate — every change to
 * this file MUST keep it green.
 */
class WordPieceTokenizer(
    private val vocab: Vocab,
    private val maxSequenceLength: Int = MAX_SEQUENCE_LENGTH,
    private val maxInputCharsPerWord: Int = MAX_INPUT_CHARS_PER_WORD,
) {

    /** Encode a single sequence: `[CLS] tokens [SEP] [PAD]…`. */
    fun encodeSingle(text: String): TokenizerOutput {
        val subwordIds = wordpieceIds(basicTokenize(text))
        val budget = maxSequenceLength - 2 // CLS + SEP
        val truncated = if (subwordIds.size > budget) subwordIds.take(budget) else subwordIds
        return assemble(listOf(truncated))
    }

    /**
     * Encode two sequences: `[CLS] textA [SEP] textB [SEP] [PAD]…`. Uses the
     * `only_first` truncation strategy — when the combined length exceeds the
     * budget, tokens are dropped from the tail of textA. textB is preserved
     * intact (matches the memory-extraction prompt format per
     * `docs/M3_M4_HANDOFF.md` §3).
     */
    fun encodePair(textA: String, textB: String): TokenizerOutput {
        val idsA = wordpieceIds(basicTokenize(textA)).toMutableList()
        val idsB = wordpieceIds(basicTokenize(textB))
        val budget = maxSequenceLength - 3 // CLS + SEP + SEP

        // only_first: shrink A from the end until combined fits. If B alone
        // already exceeds the budget, A goes to zero and B keeps its head.
        val keepA = (budget - idsB.size).coerceAtLeast(0)
        val finalA = if (idsA.size > keepA) idsA.subList(0, keepA).toList() else idsA.toList()

        val keepB = budget - finalA.size
        val finalB = if (idsB.size > keepB) idsB.take(keepB) else idsB

        return assemble(listOf(finalA, finalB))
    }

    private fun assemble(segments: List<List<Int>>): TokenizerOutput {
        val ids = LongArray(maxSequenceLength)
        val mask = LongArray(maxSequenceLength)
        var pos = 0
        ids[pos] = CLS_ID.toLong(); mask[pos] = 1; pos += 1
        for (segment in segments) {
            for (id in segment) {
                ids[pos] = id.toLong(); mask[pos] = 1; pos += 1
            }
            ids[pos] = SEP_ID.toLong(); mask[pos] = 1; pos += 1
        }
        // Remaining positions are already zero (= PAD_ID, mask 0).
        return TokenizerOutput(inputIds = ids, attentionMask = mask)
    }

    private fun wordpieceIds(words: List<String>): List<Int> {
        val out = ArrayList<Int>(words.size)
        for (word in words) {
            wordpieceWord(word, out)
        }
        return out
    }

    private fun wordpieceWord(word: String, sink: MutableList<Int>) {
        if (word.length > maxInputCharsPerWord) {
            sink.add(UNK_ID); return
        }
        val codepoints = word.toCharArray()
        var start = 0
        val pieces = ArrayList<Int>(4)
        while (start < codepoints.size) {
            var end = codepoints.size
            var matchedId: Int? = null
            var matchedEnd = -1
            while (end > start) {
                val sub = String(codepoints, start, end - start)
                val candidate = if (start > 0) "##$sub" else sub
                val id = vocab.id(candidate)
                if (id != null) {
                    matchedId = id
                    matchedEnd = end
                    break
                }
                end -= 1
            }
            if (matchedId == null) {
                // Whole word fails → drop pieces so far, emit single [UNK].
                sink.add(UNK_ID)
                return
            }
            pieces.add(matchedId)
            start = matchedEnd
        }
        sink.addAll(pieces)
    }

    // -- BasicTokenizer pipeline -------------------------------------------

    private fun basicTokenize(rawText: String): List<String> {
        val cleaned = cleanText(rawText)
        val cjkSplit = padChineseChars(cleaned)
        val words = whitespaceTokenize(cjkSplit)
        val out = ArrayList<String>(words.size)
        for (word in words) {
            // Lowercase first, then strip accents (matches HF
            // BasicTokenizer.tokenize order for do_lower_case=True).
            val lowered = word.lowercase()
            val noAccents = stripAccents(lowered)
            splitOnPunctuation(noAccents, out)
        }
        return out
    }

    private fun cleanText(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            val cp = c.code
            // 0x0 = NUL, 0xFFFD = replacement char — both stripped by HF.
            if (cp == 0 || cp == 0xFFFD || isControl(c)) continue
            if (isWhitespaceChar(c)) sb.append(' ') else sb.append(c)
        }
        return sb.toString()
    }

    private fun padChineseChars(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            if (isChineseChar(c.code)) {
                sb.append(' '); sb.append(c); sb.append(' ')
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun whitespaceTokenize(text: String): List<String> =
        text.split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun stripAccents(text: String): String {
        // NFD normalize and drop combining marks (Unicode category Mn).
        // HF's _run_strip_accents matches this.
        val normalized = unicodeNormalizeNfd(text)
        val sb = StringBuilder(normalized.length)
        for (c in normalized) {
            if (!isCombiningMark(c)) sb.append(c)
        }
        return sb.toString()
    }

    private fun splitOnPunctuation(token: String, sink: MutableList<String>) {
        if (token.isEmpty()) return
        val chars = token.toCharArray()
        var start = 0
        var startNew = true
        val pieces = ArrayList<String>()
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            if (isPunctuation(c)) {
                pieces.add(c.toString())
                startNew = true
            } else {
                if (startNew) {
                    pieces.add(c.toString())
                    startNew = false
                } else {
                    val last = pieces.size - 1
                    pieces[last] = pieces[last] + c
                }
            }
            i += 1
        }
        sink.addAll(pieces)
    }

    // -- Character classification helpers ----------------------------------

    /**
     * True for Unicode whitespace per HF (`\t`, `\n`, `\r`, ` `, plus any
     * char with category Zs/Zl/Zp). Java's [Char.isWhitespace] handles Zs/Zl/Zp
     * but excludes some that HF includes; HF includes the ASCII trio
     * explicitly so we mirror that.
     */
    private fun isWhitespaceChar(c: Char): Boolean {
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') return true
        return c.category == CharCategory.SPACE_SEPARATOR ||
            c.category == CharCategory.LINE_SEPARATOR ||
            c.category == CharCategory.PARAGRAPH_SEPARATOR
    }

    /**
     * True for control characters per HF — categories Cc and Cf — but the
     * ASCII whitespace trio is treated as whitespace, not control, by the
     * caller (cleanText replaces them with space first).
     */
    private fun isControl(c: Char): Boolean {
        if (c == '\t' || c == '\n' || c == '\r') return false
        return c.category == CharCategory.CONTROL ||
            c.category == CharCategory.FORMAT
    }

    /**
     * BERT's `_is_chinese_char`. Each CJK code point becomes its own token.
     */
    private fun isChineseChar(cp: Int): Boolean =
        (cp in 0x4E00..0x9FFF) ||
            (cp in 0x3400..0x4DBF) ||
            (cp in 0x20000..0x2A6DF) ||
            (cp in 0x2A700..0x2B73F) ||
            (cp in 0x2B740..0x2B81F) ||
            (cp in 0x2B820..0x2CEAF) ||
            (cp in 0xF900..0xFAFF) ||
            (cp in 0x2F800..0x2FA1F)

    /**
     * BERT's `_is_punctuation`: ASCII non-alphanumeric in
     * `[33,47] ∪ [58,64] ∪ [91,96] ∪ [123,126]`, plus any Unicode punctuation
     * category (Pc, Pd, Pe, Pf, Pi, Po, Ps).
     */
    private fun isPunctuation(c: Char): Boolean {
        val cp = c.code
        if ((cp in 33..47) || (cp in 58..64) || (cp in 91..96) || (cp in 123..126)) return true
        return when (c.category) {
            CharCategory.CONNECTOR_PUNCTUATION,
            CharCategory.DASH_PUNCTUATION,
            CharCategory.END_PUNCTUATION,
            CharCategory.FINAL_QUOTE_PUNCTUATION,
            CharCategory.INITIAL_QUOTE_PUNCTUATION,
            CharCategory.OTHER_PUNCTUATION,
            CharCategory.START_PUNCTUATION -> true
            else -> false
        }
    }

    private fun isCombiningMark(c: Char): Boolean =
        c.category == CharCategory.NON_SPACING_MARK ||
            c.category == CharCategory.ENCLOSING_MARK ||
            c.category == CharCategory.COMBINING_SPACING_MARK

    companion object {
        const val MAX_SEQUENCE_LENGTH: Int = 128
        const val MAX_INPUT_CHARS_PER_WORD: Int = 200

        const val PAD_ID: Int = 0
        const val UNK_ID: Int = 100
        const val CLS_ID: Int = 101
        const val SEP_ID: Int = 102
    }
}

/** Result of [WordPieceTokenizer.encodeSingle] / [WordPieceTokenizer.encodePair]. */
data class TokenizerOutput(
    val inputIds: LongArray,
    val attentionMask: LongArray,
) {
    init {
        require(inputIds.size == attentionMask.size) {
            "inputIds (${inputIds.size}) and attentionMask (${attentionMask.size}) must match"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TokenizerOutput) return false
        return inputIds.contentEquals(other.inputIds) &&
            attentionMask.contentEquals(other.attentionMask)
    }

    override fun hashCode(): Int {
        var result = inputIds.contentHashCode()
        result = 31 * result + attentionMask.contentHashCode()
        return result
    }
}

/**
 * NFD normalization. Pulled out as expect/actual so each platform uses its
 * native Unicode tables (the HF tokenizer relies on Python's
 * `unicodedata.normalize("NFD", ...)`).
 *
 *  - Android/JVM: `java.text.Normalizer.normalize(text, Normalizer.Form.NFD)`.
 *  - iOS: `NSString.precomposedStringWithCanonicalMapping`'s decomposed sibling.
 */
internal expect fun unicodeNormalizeNfd(text: String): String
