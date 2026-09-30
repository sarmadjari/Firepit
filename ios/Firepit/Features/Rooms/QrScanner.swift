@preconcurrency import AVFoundation
import SwiftUI
import os

/// Camera preview that reports QR codes as it reads them. Ported from android/app/…/rooms/QrScanner.kt.
///
/// Decoding runs on a background queue. The same code is only reported once, so a failed join can be retried by
/// scanning the next rotation rather than backing out of the screen.
struct QrScanner: View {
    let onScanned: (String) -> Void
    var enabled = true

    @State private var access = AVCaptureDevice.authorizationStatus(for: .video)

    var body: some View {
        switch access {
        case .authorized:
            CameraPreview(onScanned: onScanned, enabled: enabled)
                .ignoresSafeArea()
        case .notDetermined:
            Color.black
                .ignoresSafeArea()
                .task {
                    _ = await AVCaptureDevice.requestAccess(for: .video)
                    access = AVCaptureDevice.authorizationStatus(for: .video)
                }
        default:
            PermissionNeeded(
                title: String(localized: "Camera needed to scan"),
                message: String(
                    localized: """
                        An invite is a QR code, so Firepit needs the camera to read it. \
                        Nothing is recorded or sent anywhere.
                        """)
            )
        }
    }
}

private struct CameraPreview: UIViewRepresentable {
    let onScanned: (String) -> Void
    let enabled: Bool

    func makeCoordinator() -> ScanSession {
        ScanSession(onScanned: onScanned)
    }

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.videoGravity = .resizeAspectFill
        view.previewLayer.session = context.coordinator.session
        context.coordinator.start()
        return view
    }

    func updateUIView(_ view: PreviewView, context: Context) {
        context.coordinator.onScanned = onScanned
        context.coordinator.setEnabled(enabled)
    }

    static func dismantleUIView(_ view: PreviewView, coordinator: ScanSession) {
        coordinator.stop()
    }

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

        var previewLayer: AVCaptureVideoPreviewLayer {
            // `layerClass` makes this cast always succeed.
            layer as! AVCaptureVideoPreviewLayer
        }
    }
}

/// Owns the capture session. Configuration and start/stop run on a private queue, as AVFoundation asks, so the camera
/// never blocks the interface; recognised codes are delivered on the main thread.
private final class ScanSession: NSObject, AVCaptureMetadataOutputObjectsDelegate {
    let session = AVCaptureSession()
    var onScanned: (String) -> Void

    private let queue = DispatchQueue(label: "com.getfirepit.app.scanner")
    private let filter = ScanFilter()

    init(onScanned: @escaping (String) -> Void) {
        self.onScanned = onScanned
        super.init()
    }

    func start() {
        let session = session
        let output = AVCaptureMetadataOutput()
        let queue = queue
        output.setMetadataObjectsDelegate(self, queue: queue)
        queue.async {
            guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
                let input = try? AVCaptureDeviceInput(device: camera),
                session.canAddInput(input),
                session.canAddOutput(output)
            else { return }
            session.beginConfiguration()
            session.addInput(input)
            session.addOutput(output)
            output.metadataObjectTypes = [.qr]
            session.commitConfiguration()
            session.startRunning()
        }
    }

    func stop() {
        let session = session
        queue.async {
            session.stopRunning()
        }
    }

    func setEnabled(_ enabled: Bool) {
        filter.setEnabled(enabled)
    }

    nonisolated func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        let codes = metadataObjects.compactMap { ($0 as? AVMetadataMachineReadableCodeObject)?.stringValue }
        for code in codes where filter.shouldReport(code) {
            // Invites rotate, so the next window gives a different string and scanning resumes.
            Task { @MainActor [weak self] in
                self?.onScanned(code)
            }
        }
    }
}

/// Remembers the last code reported, so each distinct code is reported once, and whether reporting is on.
private nonisolated final class ScanFilter: Sendable {
    private struct State {
        var enabled = true
        var last: String?
    }

    private let state = OSAllocatedUnfairLock(initialState: State())

    func setEnabled(_ enabled: Bool) {
        state.withLock { $0.enabled = enabled }
    }

    func shouldReport(_ code: String) -> Bool {
        state.withLock { state in
            guard state.enabled, state.last != code else { return false }
            state.last = code
            return true
        }
    }
}
