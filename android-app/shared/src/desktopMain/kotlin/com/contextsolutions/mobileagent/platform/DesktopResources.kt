package com.contextsolutions.mobileagent.platform

/**
 * Desktop classpath resource loader (Phase 6, docs/DESKTOP_PORT_PLAN.md). The
 * small bundled assets — `vocab.txt` and the `*_config.json` / `search_defaults.json`
 * / `locations.json` files — ship as `shared/src/desktopMain/resources/` entries
 * (the Android side reads the same files via `context.assets`). This is the
 * desktop half of the Phase-6 "classpath resource for small configs/vocab" rule;
 * the cross-platform `ResourceLoader` interface that unifies it with Android's
 * `AssetManager` is folded into Phase 9 (when shared `:ui` screens need the seam).
 * Large artifacts (GGUF, ONNX) are NOT here — they're downloaded into the
 * app-data dir ([com.contextsolutions.mobileagent.inference.DesktopAuxModels]).
 *
 * [readTextOrNull] returns null on a missing resource so callers fall back to a
 * baked default (mirroring `androidModule`'s try/catch-around-`assets.open`).
 */
object DesktopResources {
    private fun stream(name: String) =
        DesktopResources::class.java.classLoader?.getResourceAsStream(name)

    /** Open a bundled resource as a stream, or null if absent. */
    fun openOrNull(name: String) = stream(name)

    /** Read a bundled resource as UTF-8 text, or null if absent. */
    fun readTextOrNull(name: String): String? =
        stream(name)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
}
