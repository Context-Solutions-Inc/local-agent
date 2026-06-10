package com.contextsolutions.mobileagent.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.image.BufferedImage

/**
 * Desktop pairing controls (PR #57; relay-only since PR #80): renders the Secure
 * Gateway **relay** pairing payload ([SettingsUiState.desktopLinkQrPayload]) as a QR
 * for the phone to scan — but ONLY while a subscription is active. Without a
 * subscription there is no pairing path, so no QR is shown (the section's "Upgrade
 * to anywhere connection" link is the only call to action). The desktop never
 * scans/unpairs from here, so [onScanned]/[onUnpair] are unused.
 */
@Composable
actual fun DesktopLinkPairingControls(
    state: SettingsUiState,
    onScanned: (String) -> Unit,
    onUnpair: () -> Unit,
) {
    // Gate on the subscription explicitly (the payload is also null when unsubscribed,
    // since Main.kt only publishes the relay QR while subscribed — belt and braces).
    val payload = state.desktopLinkQrPayload.takeIf { state.subscription.isActive }
    Column {
        if (payload != null) {
            val bitmap = remember(payload) { encodeQr(payload, QR_PX) }
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = "Desktop pairing QR", modifier = Modifier.size(220.dp))
                Spacer(Modifier.height(8.dp))
            }
            Text(
                "Scan this with the Mobile Agent app on your phone " +
                    "(Settings → Desktop Agent Connection → Scan desktop QR).",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                "Subscribe to anywhere access to show a pairing code for your phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
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
