package com.contextsolutions.localagent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.contextsolutions.localagent.platform.NativeQrScanner
import org.koin.compose.koinInject

/**
 * iOS pairing controls (relay): a "Scan desktop QR" button that presents the Swift AVFoundation
 * camera scanner ([NativeQrScanner], reusing the `NSCameraUsageDescription` key), plus an unpair
 * affordance once paired. On a successful scan the raw QR contents flow to [onScanned] →
 * `SettingsViewModel.applyScannedLink`. Structural mirror of the Android actual.
 */
@Composable
actual fun DesktopLinkPairingControls(
    state: SettingsUiState,
    onScanned: (String) -> Unit,
    onUnpair: () -> Unit,
    onPairNow: () -> Unit, // unused on mobile — the phone scans a QR, it doesn't mint one
) {
    val scanner = koinInject<NativeQrScanner>()
    Column {
        if (state.desktopLinkConfig.isPaired) {
            Text(
                "Paired with desktop ${state.desktopLinkConfig.pairedDeviceId.take(14)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scanner.present(onScanned = onScanned, onCancel = {}) }) {
                Text(if (state.desktopLinkConfig.isPaired) "Re-scan desktop QR" else "Scan desktop QR")
            }
            if (state.desktopLinkConfig.isPaired) {
                OutlinedButton(onClick = onUnpair) { Text("Unpair") }
            }
        }
    }
}
