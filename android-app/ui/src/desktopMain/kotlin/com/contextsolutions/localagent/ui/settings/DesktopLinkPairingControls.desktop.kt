package com.contextsolutions.localagent.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import com.contextsolutions.localagent.link.MobileLinkPresence
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.image.BufferedImage
import kotlinx.coroutines.delay

/**
 * Desktop pairing controls (PR #57; relay-only since PR #80; on-demand since PR #92):
 * while a subscription is active the section shows a **"Pair Now"** button when no phone
 * is paired. Clicking it mints a Secure Gateway **relay** pairing QR
 * ([SettingsUiState.desktopLinkQrPayload]) for the next ~300s, shown with a live countdown
 * ([SettingsUiState.desktopLinkQrExpiresAtEpochMs]); after the window the QR auto-hides and
 * the button returns. We no longer auto-mint (the token is only valid ~300s, so an
 * auto-shown code is usually stale). Without a subscription there is no pairing path
 * (the section's "Upgrade to anywhere connection" link is the only call to action). The
 * desktop never scans/unpairs from here, so [onScanned]/[onUnpair] are unused.
 */
@Composable
actual fun DesktopLinkPairingControls(
    state: SettingsUiState,
    onScanned: (String) -> Unit,
    onUnpair: () -> Unit,
    onPairNow: () -> Unit,
) {
    Column {
        if (!state.subscription.isActive) {
            Text(
                "Subscribe to anywhere access to show a pairing code for your phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            return@Column
        }
        val payload = state.desktopLinkQrPayload
        when {
            payload != null -> {
                val bitmap = remember(payload) { encodeQr(payload, QR_PX) }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Desktop pairing QR",
                        modifier = Modifier.size(220.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    "Scan this with the Local Agent app on your phone " +
                        "(Settings → Desktop Agent Connection → Scan desktop QR).",
                    style = MaterialTheme.typography.bodySmall,
                )
                PairingCountdown(expiresAtEpochMs = state.desktopLinkQrExpiresAtEpochMs)
            }
            // No QR + no paired phone → offer to mint one on demand. CONNECTED/OFFLINE are
            // handled by the status row above (with Disconnect), so show nothing here.
            state.mobilePresence == MobileLinkPresence.UNPAIRED -> {
                Button(onClick = onPairNow) { Text("Pair Now") }
            }
        }
    }
}

/**
 * Renders "Pairing code expires in Ns", ticking down once per second off the published
 * deadline (PR #92) — same 1s-ticker pattern as the clock screens. Hidden once elapsed.
 */
@Composable
private fun PairingCountdown(expiresAtEpochMs: Long?) {
    if (expiresAtEpochMs == null) return
    var remainingSec by remember(expiresAtEpochMs) {
        mutableStateOf(((expiresAtEpochMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0))
    }
    LaunchedEffect(expiresAtEpochMs) {
        while (remainingSec > 0) {
            delay(1_000)
            remainingSec = ((expiresAtEpochMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        }
    }
    if (remainingSec > 0) {
        Spacer(Modifier.height(4.dp))
        Text(
            "Pairing code expires in ${remainingSec}s.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

private const val QR_PX = 512

private fun encodeQr(text: String, size: Int): ImageBitmap? = runCatching {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
    val black = 0x000000
    val white = 0xFFFFFF
    for (x in 0 until size) {
        for (y in 0 until size) {
            image.setRGB(x, y, if (matrix.get(x, y)) black else white)
        }
    }
    image.toComposeImageBitmap()
}.getOrNull()
