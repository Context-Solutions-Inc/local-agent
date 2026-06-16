package com.contextsolutions.localagent.classifier

import com.contextsolutions.localagent.platform.DesktopResources

/**
 * Loads the bundled WordPiece [Vocab] from the desktop classpath
 * (`shared/src/desktopMain/resources/vocab.txt`, byte-identical to the Android
 * asset — invariant #13). `vocab.txt` is small (30,522 lines, ~230 KB) so it
 * ships as a classpath resource rather than a downloaded artifact; this is the
 * Phase-6 `ResourceLoader` "classpath resource for small configs/vocab" rule,
 * applied early because the wired [OnnxClassifierEngine]/[OnnxEmbedderEngine]
 * need a real vocab the moment they're bound (Phase 5).
 */
object DesktopVocabLoader {
    const val RESOURCE_NAME: String = "vocab.txt"

    /**
     * Read the bundled vocab, or `null` if the resource is missing — the caller
     * decides whether to fall back to a stub (the tokenizer is never invoked
     * while search/memory are disabled, so a stub keeps the graph resolvable).
     */
    fun loadOrNull(): Vocab? {
        // Reads the bundled resource via the shared DesktopResources loader.
        // Vocab.fromLines consumes the sequence eagerly inside `use`.
        return DesktopResources.openOrNull(RESOURCE_NAME)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            Vocab.fromLines(reader.lineSequence())
        }
    }
}
