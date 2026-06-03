package com.contextsolutions.mobileagent.ui.settings

import androidx.compose.runtime.Composable

/**
 * Platform half of the "Desktop Agent Connection" section (PR #57): the desktop shows a
 * pairing QR to scan; the phone shows a "Scan desktop QR" button (camera) + an
 * unpair affordance. [onScanned] receives the raw QR contents on mobile;
 * [onUnpair] forgets the paired desktop. Both are unused on desktop.
 */
@Composable
expect fun DesktopLinkPairingControls(
    state: SettingsUiState,
    onScanned: (String) -> Unit,
    onUnpair: () -> Unit,
)
