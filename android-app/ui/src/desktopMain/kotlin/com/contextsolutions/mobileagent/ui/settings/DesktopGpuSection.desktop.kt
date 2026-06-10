package com.contextsolutions.mobileagent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.contextsolutions.mobileagent.inference.GpuDevice
import com.contextsolutions.mobileagent.inference.LlamaServerDevices
import com.contextsolutions.mobileagent.preferences.DesktopGpuPreferences
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Desktop GPU device pin (PR #78). Pin `llama-server` to one GPU (e.g. `Vulkan1` for a discrete
 * NVIDIA card) so a multi-GPU box ignores the slow integrated GPU. "Detect devices" runs
 * `llama-server --list-devices` (downloading the Vulkan binary on first use); the dropdown then
 * offers "Auto — all GPUs" plus each detected device. Writing the pin invalidates the warm model
 * (wired in `Main.kt`), so the next chat turn re-launches the server pinned.
 */
@Composable
actual fun DesktopGpuSection() {
    val prefs = koinInject<DesktopGpuPreferences>()
    val devices = koinInject<LlamaServerDevices>()
    val scope = rememberCoroutineScope()

    val pin by prefs.devicePinFlow().let { flow ->
        produceState(initialValue = prefs.devicePin(), flow) { flow.collect { value = it } }
    }
    var detected by remember { mutableStateOf<List<GpuDevice>>(emptyList()) }
    var detecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))
    Text(
        text = "GPU device",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    Text(
        "On a machine with more than one GPU, pin the local model to a single device so it " +
            "doesn't fall back to a slower integrated GPU. Click “Detect devices” to see " +
            "what's available (downloads the GPU runtime the first time).",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))

    // The current pin always appears even before a probe (e.g. restored from a saved Settings
    // value), so the chip never shows a stale "Auto" for a device that isn't in `detected` yet.
    val options: List<GpuDevice?> = buildList {
        add(null) // Auto — all GPUs
        addAll(detected)
        pin?.let { id -> if (detected.none { it.id == id }) add(GpuDevice(id, "pinned")) }
    }
    val selectedLabel = pin?.let { id -> deviceLabel(detected.firstOrNull { it.id == id } ?: GpuDevice(id, "")) }
        ?: "Auto — all GPUs"

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Device: ", style = MaterialTheme.typography.bodyMedium)
        var open by remember { mutableStateOf(false) }
        AssistChip(onClick = { open = true }, label = { Text(selectedLabel) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { device ->
                DropdownMenuItem(
                    text = { Text(device?.let { deviceLabel(it) } ?: "Auto — all GPUs") },
                    onClick = { prefs.setDevicePin(device?.id); open = false },
                )
            }
        }
        OutlinedButton(
            enabled = !detecting,
            onClick = {
                error = null
                detecting = true
                scope.launch {
                    runCatching { devices.list() }
                        .onSuccess { detected = it }
                        .onFailure { error = it.message ?: "Detection failed" }
                    detecting = false
                }
            },
        ) { Text("Detect devices") }
        if (detecting) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }

    when {
        error != null -> Text(
            error!!,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
        detected.isEmpty() && !detecting -> Text(
            "No devices detected yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun deviceLabel(device: GpuDevice): String =
    if (device.description.isBlank()) device.id else "${device.id} — ${device.description}"
