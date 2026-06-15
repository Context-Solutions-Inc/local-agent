package com.contextsolutions.mobileagent.ui.settings

import androidx.compose.runtime.Composable

/**
 * Platform half of the "Desktop Agent Connection" section (PR #57): the desktop shows a
 * "Pair Now" button that mints a pairing QR on demand (PR #92); the phone shows a
 * "Scan desktop QR" button (camera) + an unpair affordance. [onScanned] receives the raw
 * QR contents on mobile; [onUnpair] forgets the paired desktop; [onPairNow] mints a fresh
 * desktop pairing QR. [onScanned]/[onUnpair] are unused on desktop; [onPairNow] is unused
 * on mobile.
 */
@Composable
expect fun DesktopLinkPairingControls(
    state: SettingsUiState,
    onScanned: (String) -> Unit,
    onUnpair: () -> Unit,
    onPairNow: () -> Unit,
)
