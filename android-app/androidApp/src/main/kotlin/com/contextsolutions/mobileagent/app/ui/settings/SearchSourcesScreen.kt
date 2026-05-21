package com.contextsolutions.mobileagent.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.contextsolutions.mobileagent.preferences.SiteConfig
import com.contextsolutions.mobileagent.preferences.SourceKind
import com.contextsolutions.mobileagent.preferences.VerticalPreferences
import com.contextsolutions.mobileagent.search.SearchSubtype

/**
 * Settings → Search sources (PR #23).
 *
 * Five vertical sections (General, News, Weather, Sports, Finance). Each
 * shows the currently-configured sites with an X to remove and a button
 * to add a new site via a small dialog (domain + kind + endpoint).
 *
 * Reorder is deferred to a follow-up — first-shipped functionality is
 * "see what's set, remove what you don't want, add what you do".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSourcesScreen(
    onBack: () -> Unit,
    viewModel: SearchSourcesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var addSubtype by remember { mutableStateOf<SearchSubtype?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search sources") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val locationLabel = state.location?.let { "${it.city}, ${it.regionCode}, ${it.country}" }
                ?: "no location set"
            Text(
                text = "Defaults seeded from: $locationLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(8.dp))

            VerticalSection(
                title = "General search",
                subtype = SearchSubtype.GENERAL,
                sites = state.prefs.sitesFor(SearchSubtype.GENERAL),
                onRemove = viewModel::removeSite,
                onAdd = { addSubtype = SearchSubtype.GENERAL },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            VerticalSection(
                title = "News",
                subtype = SearchSubtype.NEWS,
                sites = state.prefs.sitesFor(SearchSubtype.NEWS),
                onRemove = viewModel::removeSite,
                onAdd = { addSubtype = SearchSubtype.NEWS },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            VerticalSection(
                title = "Weather",
                subtype = SearchSubtype.WEATHER,
                sites = state.prefs.sitesFor(SearchSubtype.WEATHER),
                onRemove = viewModel::removeSite,
                onAdd = { addSubtype = SearchSubtype.WEATHER },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            VerticalSection(
                title = "Sports",
                subtype = SearchSubtype.SPORTS,
                sites = state.prefs.sitesFor(SearchSubtype.SPORTS),
                onRemove = viewModel::removeSite,
                onAdd = { addSubtype = SearchSubtype.SPORTS },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            VerticalSection(
                title = "Finance",
                subtype = SearchSubtype.FINANCE,
                sites = state.prefs.sitesFor(SearchSubtype.FINANCE),
                onRemove = viewModel::removeSite,
                onAdd = { addSubtype = SearchSubtype.FINANCE },
            )
        }
    }

    val targetSubtype = addSubtype
    if (targetSubtype != null) {
        AddSiteDialog(
            subtype = targetSubtype,
            onDismiss = { addSubtype = null },
            onConfirm = { domain, displayName, kind, endpoint ->
                viewModel.addSite(targetSubtype, domain, displayName, kind, endpoint)
                addSubtype = null
            },
        )
    }
}

@Composable
private fun VerticalSection(
    title: String,
    subtype: SearchSubtype,
    sites: List<SiteConfig>,
    onRemove: (SearchSubtype, String) -> Unit,
    onAdd: () -> Unit,
) {
    Text(text = title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    if (sites.isEmpty()) {
        Text(
            "No sources configured.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    } else {
        sites.forEach { site ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(site.displayName, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${site.domain} · ${site.kind.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                IconButton(onClick = { onRemove(subtype, site.domain) }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove ${site.domain}")
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    OutlinedButton(onClick = onAdd) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.padding(end = 4.dp))
        Text("Add source")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSiteDialog(
    subtype: SearchSubtype,
    onDismiss: () -> Unit,
    onConfirm: (domain: String, displayName: String, kind: SourceKind, endpoint: String) -> Unit,
) {
    var domain by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(defaultKindFor(subtype)) }
    var kindMenuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add ${subtype.name.lowercase()} source") },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(domain, displayName, kind, endpoint) },
                enabled = domain.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    label = { Text("Domain (e.g. cbc.ca)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(
                        onClick = { kindMenuOpen = true },
                        label = { Text("Kind: ${kind.name}") },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                    DropdownMenu(
                        expanded = kindMenuOpen,
                        onDismissRequest = { kindMenuOpen = false },
                    ) {
                        SourceKind.values().forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.name) },
                                onClick = { kind = option; kindMenuOpen = false },
                            )
                        }
                    }
                }
                if (kind != SourceKind.BRAVE_SITE_FILTER) {
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        label = { Text("Endpoint URL or template") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "Templates support {country}, {region}, {city}, {query}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        },
    )
}

private fun defaultKindFor(subtype: SearchSubtype): SourceKind = when (subtype) {
    SearchSubtype.GENERAL -> SourceKind.BRAVE_SITE_FILTER
    SearchSubtype.NEWS -> SourceKind.BRAVE_SITE_FILTER
    SearchSubtype.WEATHER -> SourceKind.RSS
    SearchSubtype.SPORTS -> SourceKind.BRAVE_SITE_FILTER
    SearchSubtype.FINANCE -> SourceKind.RSS
    // STOCKS has no user-editable sources; this screen never lists it, so the
    // branch only exists to keep the `when` exhaustive.
    SearchSubtype.STOCKS -> SourceKind.HTML
}
