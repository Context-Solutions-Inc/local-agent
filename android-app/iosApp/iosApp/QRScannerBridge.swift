import Foundation
import AVFoundation
import UIKit
import ComposeApp

// Swift implementation of the Kotlin `NativeQrScanner` (iOS relay pairing). Presents an
// AVFoundation QR scanner over the key window; the decoded string flows back to Kotlin
// (`SettingsViewModel.applyScannedLink`). Reuses the `NSCameraUsageDescription` Info.plist key
// (added PR #44). Registered into Koin by `iOSApp.init`.
final class QRScannerBridge: NativeQrScanner {
    func present(onScanned: @escaping (String) -> Void, onCancel: @escaping () -> Void) {
        DispatchQueue.main.async {
            guard let root = Self.topViewController() else { onCancel(); return }
            let vc = QRScannerViewController(onScanned: onScanned, onCancel: onCancel)
            vc.modalPresentationStyle = .fullScreen
            root.present(vc, animated: true)
        }
    }

    private static func topViewController() -> UIViewController? {
        let keyWindow = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }
        var top = keyWindow?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}

private final class QRScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    private let onScanned: (String) -> Void
    private let onCancel: () -> Void
    private let session = AVCaptureSession()
    private var didFinish = false

    init(onScanned: @escaping (String) -> Void, onCancel: @escaping () -> Void) {
        self.onScanned = onScanned
        self.onCancel = onCancel
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) unavailable") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            // No camera (Simulator) or permission denied → cancel back to Kotlin.
            finish(cancelled: true)
            return
        }
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { finish(cancelled: true); return }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.frame = view.layer.bounds
        preview.videoGravity = .resizeAspectFill
        view.layer.addSublayer(preview)

        let prompt = UILabel()
        prompt.text = "Scan the QR on your desktop's Settings page"
        prompt.textColor = .white
        prompt.textAlignment = .center
        prompt.numberOfLines = 0
        prompt.translatesAutoresizingMaskIntoConstraints = false

        let cancel = UIButton(type: .system)
        cancel.setTitle("Cancel", for: .normal)
        cancel.setTitleColor(.white, for: .normal)
        cancel.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        cancel.translatesAutoresizingMaskIntoConstraints = false

        view.addSubview(prompt)
        view.addSubview(cancel)
        NSLayoutConstraint.activate([
            prompt.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            prompt.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            prompt.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -40),
            cancel.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 16),
            cancel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
        ])

        DispatchQueue.global(qos: .userInitiated).async { self.session.startRunning() }
    }

    @objc private func cancelTapped() { finish(cancelled: true) }

    func metadataOutput(_ output: AVCaptureMetadataOutput,
                        didOutput metadataObjects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        guard let obj = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              obj.type == .qr, let raw = obj.stringValue else { return }
        finish(cancelled: false, value: raw)
    }

    private func finish(cancelled: Bool, value: String? = nil) {
        guard !didFinish else { return }
        didFinish = true
        if session.isRunning { session.stopRunning() }
        dismiss(animated: true) {
            if cancelled { self.onCancel() } else if let v = value { self.onScanned(v) }
        }
    }
}
