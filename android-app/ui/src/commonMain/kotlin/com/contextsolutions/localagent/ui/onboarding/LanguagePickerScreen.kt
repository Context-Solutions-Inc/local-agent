package com.contextsolutions.localagent.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.contextsolutions.localagent.i18n.StringKeys
import com.contextsolutions.localagent.language.PreferredLanguage
import com.contextsolutions.localagent.ui.i18n.tr

/**
 * First onboarding step (PR #97) — pick the app/response language before
 * anything else, so the rest of onboarding (and the whole app) renders in it.
 *
 * Choosing from the dropdown calls [onSelect], which persists the choice
 * through `LanguagePreferences` immediately: the reactive `StringCatalog`
 * re-provides `LocalStrings`, so this screen and every later step switch
 * language live as a preview. [onContinue] only marks the step decided and
 * advances. The default is [PreferredLanguage.DEFAULT] (English), so a user who
 * just taps Continue keeps English — behaviour-identical to before this screen
 * existed.
 */
@Composable
fun LanguagePickerScreen(
    selected: PreferredLanguage,
    onSelect: (PreferredLanguage) -> Unit,
    onContinue: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = tr(StringKeys.ONBOARDING_LANGUAGE_TITLE),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = tr(StringKeys.ONBOARDING_LANGUAGE_BODY),
                style = MaterialTheme.typography.bodyLarge,
            )

            Spacer(Modifier.height(8.dp))

            LanguageDropdown(
                selected = selected,
                onSelect = onSelect,
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(tr(StringKeys.ONBOARDING_NAV_CONTINUE))
            }
        }
    }
}

/**
 * Plain Material3 dropdown — same OutlinedButton-anchor + DropdownMenu pattern
 * as SettingsScreen / SearchSourcesScreen, to avoid the experimental
 * `ExposedDropdownMenuBox` API. Option labels are `nativeName · englishName`
 * (e.g. "Español · Spanish") — data fields rendered verbatim in their own
 * script, not localizable chrome.
 */
@Composable
private fun LanguageDropdown(
    selected: PreferredLanguage,
    onSelect: (PreferredLanguage) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "${selected.nativeName} · ${selected.englishName}",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            PreferredLanguage.selectable.forEach { language ->
                DropdownMenuItem(
                    text = { Text("${language.nativeName} · ${language.englishName}") },
                    onClick = {
                        onSelect(language)
                        expanded = false
                    },
                )
            }
        }
    }
}
