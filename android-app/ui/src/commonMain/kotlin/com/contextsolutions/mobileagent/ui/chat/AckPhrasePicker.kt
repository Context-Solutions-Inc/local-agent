package com.contextsolutions.mobileagent.ui.chat

import kotlin.random.Random

/**
 * Picks a short "received, working on it" acknowledgement to speak when a turn
 * starts in speaker mode. The real answer is only read aloud once fully
 * generated (we deliberately don't speak streaming tokens — it jitters), which
 * leaves a silent gap; this cue fills it the way streaming text does visually.
 *
 * [next] avoids repeating the immediately-previous phrase so back-to-back turns
 * don't sound canned.
 */
class AckPhrasePicker(
    private val phrases: List<String> = DEFAULT,
    private val random: Random = Random.Default,
) {
    private var last = -1

    fun next(): String {
        if (phrases.size <= 1) return phrases.first()
        var i = random.nextInt(phrases.size)
        if (i == last) i = (i + 1) % phrases.size
        last = i
        return phrases[i]
    }

    companion object {
        val DEFAULT = listOf(
            "Got it. Working on your response.",
            "On it. Give me a moment.",
            "Sure. Let me put that together.",
            "Okay. Working on it now.",
            "Got it. Generating your answer.",
            "Alright. One moment.",
        )

        /** Periodic "I'm still on it" cues, spoken while a long turn streams. */
        val STILL_WORKING = listOf(
            "Still working on your request.",
            "Almost there, hang tight.",
            "Still generating your answer.",
            "Bear with me, still working on it.",
            "Just a little longer.",
        )
    }
}
