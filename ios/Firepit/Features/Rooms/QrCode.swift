import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI

/// Renders an invite as a QR code. Ported from android/app/…/rooms/QrCode.kt.
///
/// Always drawn dark-on-white regardless of theme: a themed QR code with low contrast is slow or impossible to scan,
/// and scanning is the whole point.
struct QrCode: View {
    let content: String

    @State private var image: CGImage?

    var body: some View {
        Group {
            if let image {
                Image(decorative: image, scale: 1)
                    // Modules must stay crisp squares at any size.
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
            } else {
                Color(hex: 0xFFFFFF)
            }
        }
        .background(Color(hex: 0xFFFFFF))
        // The surrounding card carries the description; the pattern itself is meaningless to a screen reader.
        .accessibilityHidden(true)
        .task(id: content) {
            image = Self.render(content)
        }
    }

    /// One pixel per module. CoreImage surrounds the code with a one-module quiet zone, the margin Android asks ZXing
    /// for.
    nonisolated static func render(_ content: String) -> CGImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(content.utf8)
        // Medium correction: enough to survive a fingerprint on the screen without inflating the code so far it
        // stops scanning at arm's length.
        filter.correctionLevel = "M"
        guard let modules = filter.outputImage else { return nil }
        return CIContext().createCGImage(modules, from: modules.extent)
    }
}
