package com.contextsolutions.localagent.platform

/**
 * Swift→Kotlin bridge for the iOS camera QR scanner (relay pairing). The Swift app implements this
 * with an AVFoundation `AVCaptureMetadataOutput(.qr)` view controller presented over the key window,
 * and registers the instance into Koin via `doInitKoin(qrScanner = …)`. Reuses the
 * `NSCameraUsageDescription` Info.plist key (added PR #44 for camera capture). Mirrors the
 * NativeLlmBridge / NativeRelayBridge convention so no UIKit/AVFoundation types leak into commonMain (#23).
 */
interface NativeQrScanner {
    /**
     * Present the camera scanner. [onScanned] fires once with the raw decoded QR string (the scanner
     * dismisses itself); [onCancel] fires on user dismiss or when no camera is available (Simulator).
     */
    fun present(onScanned: (String) -> Unit, onCancel: () -> Unit)
}
