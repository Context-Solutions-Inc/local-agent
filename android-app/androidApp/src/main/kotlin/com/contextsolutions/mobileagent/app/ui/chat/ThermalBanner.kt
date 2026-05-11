package com.contextsolutions.mobileagent.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.contextsolutions.mobileagent.inference.ThermalStatus

/**
 * M6 Phase E — thermal warning banner above the chat input (PRD §4.3).
 *
 * Three visual states keyed off [ThermalStatus]:
 *
 *  - [ThermalStatus.NONE] / [ThermalStatus.LIGHT] — invisible, no-op.
 *  - [ThermalStatus.MODERATE] / [ThermalStatus.SEVERE] — dismissible
 *    banner. Inference still runs, just slower.
 *  - [ThermalStatus.CRITICAL] or higher — full-width block; the caller
 *    disables the send button.
 *
 * Dismissal is **per-session, per-state** — if the user dismisses the
 * MODERATE banner and the device escalates to SEVERE, the banner
 * reappears (different thermal level resets the dismissal). The CRITICAL
 * block is non-dismissible.
 *
 * `liveRegion = Polite` so TalkBack reads the warning text when it
 * appears without interrupting whatever the user is currently focused
 * on.
 */
@Composable
fun ThermalBanner(thermal: ThermalStatus) {
    if (thermal == ThermalStatus.NONE || thermal == ThermalStatus.LIGHT) return

    // Dismissal state keyed on the current thermal level so escalation
    // re-shows the banner.
    var dismissedFor by remember { mutableStateOf<ThermalStatus?>(null) }

    if (thermal.isBlocking) {
        ThermalCriticalBlock()
        return
    }

    if (dismissedFor == thermal) return

    val color = MaterialTheme.colorScheme.errorContainer
    val onColor = MaterialTheme.colorScheme.onErrorContainer

    Surface(
        color = color,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = onColor,
            )
            Text(
                text = "Your device is running warm. Responses may be slower.",
                color = onColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { dismissedFor = thermal },
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Dismiss thermal warning",
                    tint = onColor,
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ThermalCriticalBlock() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = "Device too hot for generation",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Text(
                text = "Wait a few minutes for the device to cool. Send is " +
                    "disabled until the thermal state clears.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}
