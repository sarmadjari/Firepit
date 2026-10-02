import SwiftUI

/// The Firepit flame: the shape on the app icon (design/artwork/icon-app.svg) without the icon's ground or pit rim,
/// for the small places where the rim would be too thin to read. Android's status bar icon is the same flame.
///
/// One colour, so it takes whatever foreground style it is given. Drawn at its own proportions, as tall as the frame
/// allows and centred in it.
nonisolated struct FirepitMark: Shape {
    func path(in rect: CGRect) -> Path {
        let bounds = Self.bounds
        let scale = min(rect.width / bounds.width, rect.height / bounds.height)
        return Self.flame.applying(
            CGAffineTransform(translationX: rect.midX, y: rect.midY)
                .scaledBy(x: scale, y: scale)
                .translatedBy(x: -bounds.midX, y: -bounds.midY))
    }

    /// The artwork's flame path as drawn, in its own units (a box 382.5 wide and 612 tall). `DesignSystemTests`
    /// checks it still matches the SVG.
    static let flame: Path = {
        var path = Path()
        func curve(_ x1: CGFloat, _ y1: CGFloat, _ x2: CGFloat, _ y2: CGFloat, _ x: CGFloat, _ y: CGFloat) {
            path.addCurve(to: CGPoint(x: x, y: y), control1: CGPoint(x: x1, y: y1), control2: CGPoint(x: x2, y: y2))
        }
        path.move(to: CGPoint(x: 216.02, y: 611.195))
        curve(221.998, 614.373, 228.304, 607.491, 224.644, 601.795)
        curve(204.778, 570.876, 185.966, 518.848, 215.938, 451.843)
        curve(265.92, 340.106, 296.334, 282.234, 296.334, 282.234)
        curve(296.334, 282.234, 312.511, 349.77, 356.363, 409.819)
        curve(398.568, 467.612, 421.669, 540.297, 384.427, 600.848)
        curve(380.932, 606.531, 387.095, 613.236, 393.034, 610.197)
        curve(439.134, 586.615, 490.84, 539.312, 496.674, 445.18)
        curve(498.825, 416.416, 495.599, 376.146, 479.468, 325.329)
        curve(458.727, 260.923, 433.229, 230.87, 418.476, 217.964)
        curve(414.063, 214.103, 407.2, 217.525, 407.562, 223.377)
        curve(411.861, 292.871, 385.717, 310.506, 370.836, 270.763)
        curve(364.893, 254.889, 361.427, 227.433, 361.427, 193.997)
        curve(361.427, 138.332, 345.277, 81.03, 309.672, 34.466)
        curve(300.413, 22.357, 289.579, 11.042, 277.149, 1.393)
        curve(272.649, -2.101, 266.126, 1.411, 266.538, 7.093)
        curve(269.272, 44.829, 266.795, 152.978, 171.914, 282.182)
        curve(85.885, 402.033, 119.221, 494.078, 131.05, 519.008)
        curve(153.666, 566.767, 185.212, 594.814, 216.02, 611.195)
        path.closeSubpath()
        return path
    }()

    private static let bounds = flame.boundingRect
}

#Preview("Mark") {
    HStack(spacing: 24) {
        FirepitMark()
            .fill(FirepitColors.primary)
            .frame(width: 96, height: 96)
        FirepitMark()
            .fill(FirepitColors.textPrimary)
            .frame(width: 48, height: 48)
        FirepitMark()
            .fill(FirepitColors.textSecondary)
            .frame(width: 20, height: 20)
    }
    .padding()
    .background(FirepitColors.surface)
}
