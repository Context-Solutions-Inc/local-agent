package com.contextsolutions.localagent.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import com.contextsolutions.localagent.platform.LocaleProvider
import com.contextsolutions.localagent.preferences.LocationCatalog
import org.koin.compose.koinInject

/**
 * Onboarding location step — captures only the user's **country** (PR #37).
 * The country picks the country-appropriate default sources (Environment
 * Canada for CA, NWS for US, BBC for GB, …). The specific city/state for a
 * weather lookup is asked at query time and resolved against the catalog, so
 * onboarding no longer collects a region or city.
 *
 * Auto-fills the country from the device locale on first composition. "Use
 * device default" skips the picker entirely and falls back to the first
 * country in the catalog.
 */
@Composable
fun LocationPickerScreen(
    catalog: LocationCatalog,
    onSave: (country: String) -> Unit,
    onSkip: () -> Unit,
) {
    val countries = remember { catalog.countries() }
    val localeProvider = koinInject<LocaleProvider>()
    val deviceCountryHint = remember(localeProvider) { localeProvider.countryCode() }

    var selectedCountryCode by remember {
        mutableStateOf(
            countries.firstOrNull { it.code == deviceCountryHint }?.code
                ?: countries.firstOrNull()?.code
                ?: "",
        )
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Which country are you in?",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "Local Agent fetches weather, news, sports, and finance " +
                    "from sources that make sense for your country. You can " +
                    "change these later in Settings. For weather, just tell me " +
                    "the city and state or province when you ask.",
                style = MaterialTheme.typography.bodyLarge,
            )

            Spacer(Modifier.height(8.dp))

            LabelledDropdown(
                label = "Country",
                value = countries.firstOrNull { it.code == selectedCountryCode }?.name.orEmpty(),
                options = countries.map { it.code to it.name },
                onSelect = { selectedCountryCode = it },
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { onSave(selectedCountryCode) },
                enabled = selectedCountryCode.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save and continue")
            }

            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Use device default")
            }
        }
    }
}

/**
 * Plain Material3 dropdown — same pattern as SettingsScreen.LanguageDropdown
 * (OutlinedButton anchor + DropdownMenu) to avoid pulling in
 * `ExposedDropdownMenuBox` and its experimental-API ceremony.
 */
@Composable
private fun LabelledDropdown(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            onClick = { expanded = true },
            enabled = options.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = value.ifEmpty { "Select..." }, modifier = Modifier.fillMaxWidth())
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (code, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(code)
                        expanded = false
                    },
                )
            }
        }
    }
}
