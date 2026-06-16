package com.contextsolutions.localagent.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.contextsolutions.localagent.i18n.StringKeys
import com.contextsolutions.localagent.i18n.Strings

/**
 * Compose access to the active [Strings] (PR #96 i18n seam). Seeded once at
 * each composition root (Android `MainActivity`, desktop `Main.kt`) from
 * `StringCatalog.active`; `staticCompositionLocalOf` is correct because the
 * reference only changes on a deliberate language flip — a full-tree re-provide
 * is fine and rare, and reads stay allocation-free.
 *
 * Screens call [tr] / [trPlural] / [trList] with a [StringKeys] constant. This
 * PR lands the seam only — no screen reads it yet; migrating the ~525 Compose
 * literals to `tr(...)` is the documented follow-up.
 */
val LocalStrings: ProvidableCompositionLocal<Strings> = staticCompositionLocalOf {
    // Defaulting to English rather than erroring keeps previews / any
    // un-wrapped subtree usable; production always provides the catalog value.
    Strings.ENGLISH
}

/** Simple localized string for [key] (a [StringKeys] constant), with `%1$s`/`%2$d` args. */
@Composable
@ReadOnlyComposable
fun tr(key: String, vararg args: Any?): String = LocalStrings.current.get(key, *args)

/** Plural localized string for [key], selecting the category for [count]. */
@Composable
@ReadOnlyComposable
fun trPlural(key: String, count: Int, vararg args: Any?): String =
    LocalStrings.current.plural(key, count, *args)

/** Phrase list for [key] (voice/ack lists). Empty if absent. */
@Composable
@ReadOnlyComposable
fun trList(key: String): List<String> = LocalStrings.current.list(key)
