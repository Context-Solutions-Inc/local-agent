package com.contextsolutions.localagent.job

import java.io.File

/**
 * Best-effort `.gitignore` matcher used ONLY to hide clutter (node_modules, seen.json, *.log,
 * dotfiles) in the desktop job-folder chooser. This is NOT a full gitignore implementation:
 * it matches on the entry's basename only, supports `#` comments, trailing-`/` (directory-only),
 * leading-`/` (anchored basename), and `*` wildcards within a segment. Anything it can't read or
 * parse degrades to [EMPTY] (show everything) — filtering here is a nicety, never a hard gate.
 */
class GitignoreMatcher private constructor(private val patterns: List<Pattern>) {

    private class Pattern(val regex: Regex, val directoryOnly: Boolean)

    /** True if [entry]'s basename matches any collected ignore pattern. */
    fun isIgnored(entry: File): Boolean {
        val name = entry.name
        if (name.isEmpty()) return false
        val isDir = entry.isDirectory
        return patterns.any { p ->
            (!p.directoryOnly || isDir) && p.regex.matches(name)
        }
    }

    companion object {
        /** A matcher that ignores nothing. */
        val EMPTY = GitignoreMatcher(emptyList())

        private const val MAX_DEPTH = 25

        /**
         * Collect `.gitignore` files from [dir] up the tree, stopping at and including the
         * directory that contains a `.git` entry (bounded by [MAX_DEPTH]). Walking up is required:
         * the ignore that hides `node_modules` may live a level above the folder being browsed.
         * Any failure yields [EMPTY].
         */
        fun forDirectory(dir: File?): GitignoreMatcher = runCatching {
            if (dir == null) return EMPTY
            val patterns = mutableListOf<Pattern>()
            var current: File? = dir.absoluteFile
            var depth = 0
            while (current != null && depth < MAX_DEPTH) {
                val gitignore = File(current, ".gitignore")
                if (gitignore.isFile) {
                    parse(gitignore.readText(), patterns)
                }
                val atRepoRoot = File(current, ".git").exists()
                if (atRepoRoot) break
                current = current.parentFile
                depth++
            }
            if (patterns.isEmpty()) EMPTY else GitignoreMatcher(patterns)
        }.getOrDefault(EMPTY)

        private fun parse(text: String, into: MutableList<Pattern>) {
            for (raw in text.lineSequence()) {
                var line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val directoryOnly = line.endsWith("/")
                if (directoryOnly) line = line.dropLast(1)
                if (line.startsWith("/")) line = line.drop(1)  // anchored ⇒ basename match
                // basename only: drop any remaining path segments
                line = line.substringAfterLast('/')
                if (line.isEmpty()) continue
                into += Pattern(globToRegex(line), directoryOnly)
            }
        }

        /** Glob → regex on a basename: `*` ⇒ `.*`, all other regex metachars escaped. */
        private fun globToRegex(glob: String): Regex {
            val sb = StringBuilder()
            for (c in glob) {
                when (c) {
                    '*' -> sb.append(".*")
                    else -> sb.append(Regex.escape(c.toString()))
                }
            }
            return Regex(sb.toString())
        }
    }
}
