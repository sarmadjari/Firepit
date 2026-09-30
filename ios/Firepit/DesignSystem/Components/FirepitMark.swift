import SwiftUI

/// The Firepit mark — flame, hollow and pit rim — drawn from the same path data as brand/firepit-mark.svg in the
/// Android repo (a 512-unit canvas). One colour, so it takes whatever foreground style it is given.
nonisolated struct FirepitMark: Shape {
    func path(in rect: CGRect) -> Path {
        let side = min(rect.width, rect.height)
        let unit = side / 512
        let origin = CGPoint(x: rect.midX - side / 2, y: rect.midY - side / 2)
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
            CGPoint(x: origin.x + x * unit, y: origin.y + y * unit)
        }

        var flame = Path()
        flame.move(to: p(262, 122))
        flame.addCurve(to: p(336, 268), control1: p(306, 174), control2: p(336, 216))
        flame.addCurve(to: p(256, 356), control1: p(336, 320), control2: p(300, 356))
        flame.addCurve(to: p(176, 270), control1: p(212, 356), control2: p(176, 320))
        flame.addCurve(to: p(216, 182), control1: p(176, 236), control2: p(192, 206))
        flame.addCurve(to: p(240, 252), control1: p(214, 214), control2: p(222, 238))
        flame.addCurve(to: p(262, 122), control1: p(258, 224), control2: p(266, 172))
        flame.closeSubpath()
        // The hollow: drawn the other way round so non-zero filling leaves it empty, like the SVG's even-odd rule.
        flame.move(to: p(256, 248))
        flame.addCurve(to: p(221, 311), control1: p(236, 274), control2: p(221, 289))
        flame.addCurve(to: p(256, 344), control1: p(221, 332), control2: p(236, 344))
        flame.addCurve(to: p(291, 311), control1: p(276, 344), control2: p(291, 332))
        flame.addCurve(to: p(256, 248), control1: p(291, 289), control2: p(276, 274))
        flame.closeSubpath()

        var rim = Path()
        rim.move(to: p(170, 384))
        rim.addQuadCurve(to: p(342, 384), control: p(256, 416))

        var mark = flame
        mark.addPath(rim.strokedPath(StrokeStyle(lineWidth: 22 * unit, lineCap: .round)))
        return mark
    }
}

#Preview("Mark") {
    HStack(spacing: 24) {
        FirepitMark()
            .fill(FirepitColors.primary)
            .frame(width: 96, height: 96)
        FirepitMark()
            .fill(FirepitColors.textPrimary)
            .frame(width: 48, height: 48)
    }
    .padding()
    .background(FirepitColors.surface)
}
