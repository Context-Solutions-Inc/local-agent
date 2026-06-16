package com.contextsolutions.localagent.inference

/**
 * Strips the reasoning "channel" a *thinking* GGUF emits before its answer from
 * the live desktop generation stream (docs/DESKTOP_PORT_PLAN.md — desktop chat).
 *
 * Some Gemma 4 conversions (e.g. the E4B reasoning build) ship a chat template
 * that renders the model's chain-of-thought as a `<|channel>thought … <channel|>`
 * span (plus a stray `<|think|>` primer token) and carries a `strip_thinking`
 * macro that removes those spans — but only when *re-rendering history*. The
 * live generation the desktop UI shows still contains them. This ports that same
 * removal to the output stream so the chat shows just the answer, matching the
 * Android E2B build (which doesn't reason out loud).
 *
 * Mirrors the macro exactly: it removes every `<|channel>…<channel|>` span (the
 * final answer is emitted *outside* any channel) and drops the literal `<|think|>`
 * token; everything else passes through untouched (tool-call markers, etc. are
 * left alone). **Streaming-aware:** markers can straddle token boundaries, so a
 * short tail (< the longest marker) is held back until more text arrives or
 * [finish] flushes it. A no-op for output that contains none of these markers.
 */
class ThinkingStripper {

    private val buf = StringBuilder()
    private var inChannel = false
    private var started = false

    /** Feed a generated chunk; returns the cleaned text to show (may be empty). */
    fun push(text: String): String {
        buf.append(text)
        return leadTrim(drain(flush = false))
    }

    /** Flush any held-back tail at end-of-generation. */
    fun finish(): String = leadTrim(drain(flush = true))

    /**
     * Drop leading whitespace until the first visible character — the answer
     * follows a dropped channel (`…<channel|>\n{answer}`), so without this it
     * would open with a stray newline/space. Mirrors the macro's trailing `| trim`
     * (start side; trailing whitespace is the consumer's concern).
     */
    private fun leadTrim(text: String): String {
        if (started) return text
        val trimmed = text.trimStart()
        if (trimmed.isNotEmpty()) started = true
        return trimmed
    }

    private fun drain(flush: Boolean): String {
        val s = buf.toString()
        val out = StringBuilder()
        var idx = 0
        while (idx < s.length) {
            if (!inChannel) {
                val open = s.indexOf(OPEN, idx)
                val think = s.indexOf(THINK, idx)
                val next = when {
                    open < 0 -> think
                    think < 0 -> open
                    else -> minOf(open, think)
                }
                if (next < 0) {
                    // No complete marker ahead — emit the safe portion, holding back a
                    // tail that could still grow into a marker (unless flushing).
                    val keepFrom = if (flush) s.length else maxOf(idx, s.length - MAX_PARTIAL)
                    out.append(s, idx, keepFrom)
                    idx = keepFrom
                    break
                }
                out.append(s, idx, next)
                if (next == open) {
                    idx = next + OPEN.length
                    inChannel = true
                } else {
                    idx = next + THINK.length // drop the bare primer token
                }
            } else {
                val close = s.indexOf(CLOSE, idx)
                if (close < 0) {
                    // Still inside the thought channel — drop it, keeping only a tail
                    // that might be a split close marker (unless flushing).
                    idx = if (flush) s.length else maxOf(idx, s.length - MAX_PARTIAL)
                    break
                }
                idx = close + CLOSE.length
                inChannel = false
            }
        }
        buf.setLength(0)
        if (idx < s.length) buf.append(s, idx, s.length)
        return out.toString()
    }

    private companion object {
        const val OPEN = "<|channel>"
        const val CLOSE = "<channel|>"
        const val THINK = "<|think|>"
        val MAX_PARTIAL = maxOf(OPEN.length, CLOSE.length, THINK.length) - 1
    }
}
