package com.contextsolutions.mobileagent.app.ui.onboarding

import android.content.Context
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.contextsolutions.mobileagent.preferences.LocationCatalog
import java.util.Locale

/**
 * Onboarding step 4 (PR #23) — captures the user's country, region, and
 * city so vertical search routing can pick country-appropriate default
 * sources (Environment Canada for Canada, NWS for US, BBC for GB, etc.)
 * and substitute the city into adapter URL templates.
 *
 * Auto-fills the country from the device locale on first composition. The
 * region/city dropdowns repopulate when the user changes the parent
 * selection. "Use device default" skips the picker entirely and falls
 * back to the first country in the catalog.
 */
@Composable
fun LocationPickerScreen(
    catalog: LocationCatalog,
    onSave: (country: String, regionCode: String, city: String) -> Unit,
    onSkip: () -> Unit,
) {
    val countries = remember { catalog.countries() }
    val context = LocalContext.current
    val deviceCountryHint = remember(context) { detectDeviceCountry(context) }

    var selectedCountryCode by remember {
        mutableStateOf(
            countries.firstOrNull { it.code == deviceCountryHint }?.code
                ?: countries.firstOrNull()?.code
                ?: "",
        )
    }
    val selectedCountry = remember(selectedCountryCode) {
        countries.firstOrNull { it.code == selectedCountryCode }
    }

    var selectedRegionCode by remember(selectedCountryCode) {
        mutableStateOf(selectedCountry?.regions?.firstOrNull()?.code ?: "")
    }
    val selectedRegion = remember(selectedCountryCode, selectedRegionCode) {
        selectedCountry?.regions?.firstOrNull { it.code == selectedRegionCode }
    }

    var selectedCity by remember(selectedCountryCode, selectedRegionCode) {
        mutableStateOf(selectedRegion?.cities?.firstOrNull()?.name.orEmpty())
    }

    // When the country list reshapes the region picker, force-pick the
    // first region/city so the Continue button has valid data even if the
    // user immediately taps Save without scrolling.
    LaunchedEffect(selectedCountryCode) {
        val newRegion = selectedCountry?.regions?.firstOrNull()
        selectedRegionCode = newRegion?.code.orEmpty()
        selectedCity = newRegion?.cities?.firstOrNull()?.name.orEmpty()
    }
    LaunchedEffect(selectedRegionCode) {
        if (selectedRegion != null && selectedRegion.cities.none { it.name == selectedCity }) {
            selectedCity = selectedRegion.cities.firstOrNull()?.name.orEmpty()
        }
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
                text = "Where are you?",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "Mobile Agent fetches weather, news, sports, and finance " +
                    "from sources that make sense for your region. You can " +
                    "change these later in Settings.",
                style = MaterialTheme.typography.bodyLarge,
            )

            Spacer(Modifier.height(8.dp))

            LabelledDropdown(
                label = "Country",
                value = countries.firstOrNull { it.code == selectedCountryCode }?.name.orEmpty(),
                options = countries.map { it.code to it.name },
                onSelect = { selectedCountryCode = it },
            )

            LabelledDropdown(
                label = "State / Province",
                value = selectedCountry?.regions
                    ?.firstOrNull { it.code == selectedRegionCode }?.name.orEmpty(),
                options = selectedCountry?.regions.orEmpty().map { it.code to it.name },
                onSelect = { selectedRegionCode = it },
            )

            LabelledDropdown(
                label = "City",
                value = selectedCity,
                options = selectedRegion?.cities.orEmpty().map { it.name to it.name },
                onSelect = { selectedCity = it },
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    onSave(selectedCountryCode, selectedRegionCode, selectedCity)
                },
                enabled = selectedCountryCode.isNotBlank() && selectedCity.isNotBlank(),
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

private fun detectDeviceCountry(context: Context): String {
    val locales = context.resources.configuration.locales
    val country = if (!locales.isEmpty) locales.get(0)?.country else null
    return country?.uppercase(Locale.ROOT).orEmpty()
}
