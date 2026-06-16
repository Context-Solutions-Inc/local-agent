package com.contextsolutions.localagent.i18n

import com.contextsolutions.localagent.language.LanguagePreferences
import com.contextsolutions.localagent.language.PreferredLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The single source of localized strings for both Compose UI and the
 * non-Compose agent layer. Overlays the user's selected-language pack on top of
 * the always-present English floor ([EnglishStrings]) and exposes it two ways:
 *  - [active] — reactive, drives the Compose tree (re-provided on a language
 *    flip), built from [LanguagePreferences.preferredLanguageFlow].
 *  - [stringsFor] — synchronous snapshot, for per-turn agent use where blocking
 *    on a pack load isn't acceptable.
 */
interface StringCatalog {
    val active: StateFlow<Strings>
    fun stringsFor(language: PreferredLanguage): Strings
}

/**
 * Default [StringCatalog]. English is the in-code floor (never loaded, never
 * fails), so [Strings] always resolves; non-English packs come from the
 * injected [StringPackLoader] (JSON), parsed on the [active] collector and
 * overlaid on English. With only the English floor shipping today every load
 * returns null and [active] stays English — the loader seam is live and
 * exercised by tests but no translated pack is bundled yet.
 *
 * [stringsFor] reads [active]'s current value rather than a separate cache:
 * the agent resolves a turn's language from the same [LanguagePreferences] that
 * drives [active], so they agree except for the brief window right after a
 * language flip — where returning the (still-English) snapshot is the correct
 * graceful fallback anyway. This keeps the type free of any concurrency
 * primitive (`synchronized`/`@Volatile` aren't available in `commonMain` with
 * an `iosMain` set); `StateFlow.value` is the only shared read and is safe.
 */
class DefaultStringCatalog(
    private val loader: StringPackLoader,
    languagePreferences: LanguagePreferences,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : StringCatalog {

    private val enPack: StringPack = EnglishStrings.pack
    private val englishStrings = Strings(enPack, enPack)

    override val active: StateFlow<Strings> =
        languagePreferences.preferredLanguageFlow()
            .map { language -> resolve(language) }
            .stateIn(scope, SharingStarted.Eagerly, englishStrings)

    override fun stringsFor(language: PreferredLanguage): Strings {
        if (language == PreferredLanguage.EN) return englishStrings
        val current = active.value
        return if (current.active.code == language.code) current else englishStrings
    }

    private suspend fun resolve(language: PreferredLanguage): Strings {
        if (language == PreferredLanguage.EN) return englishStrings
        val pack = runCatching {
            loader.load(language.code)?.let { StringPack.parse(it, language.code) }
        }.getOrNull() ?: return englishStrings
        return Strings(pack, enPack)
    }
}
