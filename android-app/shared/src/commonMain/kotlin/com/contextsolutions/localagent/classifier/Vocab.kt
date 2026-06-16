package com.contextsolutions.localagent.classifier

/**
 * In-memory representation of a HuggingFace WordPiece vocabulary file. Each
 * line of `vocab.txt` maps to one token; the line index (0-based) is the
 * token id. For `distilbert-base-uncased` this is exactly 30,522 entries
 * with reserved positions:
 *
 *  - 0   `[PAD]`
 *  - 100 `[UNK]`
 *  - 101 `[CLS]`
 *  - 102 `[SEP]`
 *  - 103 `[MASK]`
 */
class Vocab(
    private val tokenToId: Map<String, Int>,
    private val idToToken: Map<Int, String>,
) {
    val size: Int get() = tokenToId.size

    fun id(token: String): Int? = tokenToId[token]
    fun token(id: Int): String? = idToToken[id]
    fun contains(token: String): Boolean = tokenToId.containsKey(token)

    companion object {
        /**
         * Build a [Vocab] from the lines of `vocab.txt`. Empty lines are
         * preserved as their line index — matches HuggingFace, which never
         * skips lines. Caller must ensure the file is read with the original
         * line ordering.
         */
        fun fromLines(lines: Sequence<String>): Vocab {
            val tokenToId = HashMap<String, Int>()
            val idToToken = HashMap<Int, String>()
            lines.forEachIndexed { index, line ->
                val token = line.trimEnd('\r', '\n')
                tokenToId[token] = index
                idToToken[index] = token
            }
            return Vocab(tokenToId, idToToken)
        }
    }
}
