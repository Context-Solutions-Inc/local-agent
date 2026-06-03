package com.contextsolutions.mobileagent.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
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
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Android pairing controls (PR #57): a "Scan desktop QR" button that launches the
 * zxing-android-embedded scanner (it requests the CAMERA permission itself), plus
 * an unpair affordance once paired. On a successful scan the raw QR contents flow
 * to [onScanned] → `SettingsViewModel.applyScannedLink`.
 */
@Composable
actual fun DesktopLinkPairingControls(
    state: SettingsUiState,
    onScanned: (String) -> Unit,
    onUnpair: () -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(onScanned)
    }
    Column {
        if (state.desktopLinkConfig.isPaired) {
            Text(
                "Paired with desktop ${state.desktopLinkConfig.pairedDeviceId.take(14)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    launcher.launch(
                        ScanOptions()
                            .setOrientationLocked(false)
                            .setBeepEnabled(false)
                            .setPrompt("Scan the QR on your desktop's Settings page"),
                    )
                },
            ) {
                Text(if (state.desktopLinkConfig.isPaired) "Re-scan desktop QR" else "Scan desktop QR")
            }
            if (state.desktopLinkConfig.isPaired) {
                OutlinedButton(onClick = onUnpair) { Text("Unpair") }
            }
        }
    }
}
