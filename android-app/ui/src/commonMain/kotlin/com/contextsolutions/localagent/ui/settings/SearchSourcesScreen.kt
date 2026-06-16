package com.contextsolutions.localagent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import com.contextsolutions.localagent.preferences.SiteConfig
import com.contextsolutions.localagent.preferences.SourceKind
import com.contextsolutions.localagent.search.SearchSubtype

/**
 * Settings → Search sources (PR #23).
 *
 * Five vertical sections (General, News, Weather, Sports, Finance). Each
 * shows the currently-configured sites with a pencil to edit and an X to
 * remove, plus a button to add a new site. Add and edit share one dialog
 * (domain + kind + endpoint), driven by [SiteDialogRequest].
 *
 * Reorder is deferred to a follow-up — first-shipped functionality is
 * "see what's set, edit/remove what you don't want, add what you do".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSourcesScreen(
    onBack: () -> Unit,
    viewModel: SearchSourcesViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var dialog by remember { mutableStateOf<SiteDialogRequest?>(null) }

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
            // Onboarding captures country only (PR #37); region/city are empty
            // and resolved per-query for weather, so show whatever is present.
            val locationLabel = state.location
                ?.let { listOf(it.city, it.regionCode, it.country).filter(String::isNotBlank).joinToString(", ") }
                ?.takeIf { it.isNotBlank() }
                ?: "no location set"
            Text(
                text = "Defaults seeded from: $locationLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(8.dp))

            val sections = listOf(
                "General search" to SearchSubtype.GENERAL,
                "News" to SearchSubtype.NEWS,
                "Weather" to SearchSubtype.WEATHER,
                "Sports" to SearchSubtype.SPORTS,
                "Finance" to SearchSubtype.FINANCE,
            )
            sections.forEachIndexed { i, (title, subtype) ->
                VerticalSection(
                    title = title,
                    subtype = subtype,
                    sites = state.prefs.sitesFor(subtype),
                    onRemove = viewModel::removeSite,
                    onAdd = { dialog = SiteDialogRequest(subtype, existing = null) },
                    onEdit = { site -> dialog = SiteDialogRequest(subtype, existing = site) },
                )
                if (i < sections.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                }
            }
        }
    }

    dialog?.let { req ->
        SiteDialog(
            subtype = req.subtype,
            existing = req.existing,
            onDismiss = { dialog = null },
            onConfirm = { domain, displayName, kind, endpoint ->
                if (req.existing == null) {
                    viewModel.addSite(req.subtype, domain, displayName, kind, endpoint)
                } else {
                    viewModel.editSite(req.subtype, req.existing.domain, domain, displayName, kind, endpoint)
                }
                dialog = null
            },
        )
    }
}

/** Drives the shared add/edit dialog. [existing] null = add, non-null = edit. */
private data class SiteDialogRequest(
    val subtype: SearchSubtype,
    val existing: SiteConfig?,
)

@Composable
private fun VerticalSection(
    title: String,
    subtype: SearchSubtype,
    sites: List<SiteConfig>,
    onRemove: (SearchSubtype, String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (SiteConfig) -> Unit,
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
                IconButton(onClick = { onEdit(site) }) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit ${site.domain}")
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
private fun SiteDialog(
    subtype: SearchSubtype,
    existing: SiteConfig?,
    onDismiss: () -> Unit,
    onConfirm: (domain: String, displayName: String, kind: SourceKind, endpoint: String) -> Unit,
) {
    var domain by remember { mutableStateOf(existing?.domain ?: "") }
    var displayName by remember { mutableStateOf(existing?.displayName ?: "") }
    var endpoint by remember { mutableStateOf(existing?.endpointTemplate ?: "") }
    var kind by remember { mutableStateOf(existing?.kind ?: defaultKindFor(subtype)) }
    var kindMenuOpen by remember { mutableStateOf(false) }

    // Domain/endpoint are URLs — force a URI keyboard with no auto-capitalization
    // so the IME doesn't upper-case the letter after a typed `.` or `:`
    // (sentence-capitalization, the default for plain text fields).
    val urlKeyboard = KeyboardOptions(
        keyboardType = KeyboardType.Uri,
        capitalization = KeyboardCapitalization.None,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            val verb = if (existing == null) "Add" else "Edit"
            Text("$verb ${subtype.name.lowercase()} source")
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // BRAVE_SITE_FILTER hides the endpoint field; don't carry a
                    // stale URL into it (e.g. after switching kinds) — let the
                    // ViewModel default it to the domain.
                    val ep = if (kind == SourceKind.BRAVE_SITE_FILTER) "" else endpoint
                    onConfirm(domain, displayName, kind, ep)
                },
                enabled = domain.isNotBlank(),
            ) { Text(if (existing == null) "Add" else "Save") }
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
                    keyboardOptions = urlKeyboard,
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
                        keyboardOptions = urlKeyboard,
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
    SearchSubtype.FINANCE -> SourceKind.BRAVE_SITE_FILTER
}
