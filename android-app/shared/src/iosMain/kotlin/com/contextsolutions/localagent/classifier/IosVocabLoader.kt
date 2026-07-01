package com.contextsolutions.localagent.classifier

import com.contextsolutions.localagent.platform.IosResources

/**
 * Loads the bundled WordPiece [Vocab] from the iOS app bundle (`vocab.txt`, added to
 * the `iosApp` target's Copy Bundle Resources; byte-identical to the Android asset /
 * desktop resource — invariant #13). Mirrors `DesktopVocabLoader`.
 *
 * `vocab.txt` is small (30,522 lines, ~230 KB) so it ships in the IPA rather than
 * downloading. It reaches `:shared` via `NSBundle` (the `:ui` Compose-resource
 * accessors aren't visible where the `Vocab` single is bound in `iosModule`).
 */
object IosVocabLoader {

    /**
     * Read the bundled vocab, or `null` if the resource is missing — `iosModule`
     * falls back to a stub (the tokenizer is never invoked while classifier/embedder
     * are absent, so a stub keeps the Koin graph resolvable).
     */
    fun loadOrNull(): Vocab? {
        val content = IosResources.readTextOrNull("vocab.txt") ?: return null
        return Vocab.fromLines(content.lineSequence())
    }
}
