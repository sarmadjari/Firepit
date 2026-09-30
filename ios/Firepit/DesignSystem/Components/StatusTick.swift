import FirepitModel
import SwiftUI

/// The honest ticks, ported from android/core/designsystem/…/component/StatusTick.kt.
///
/// There is deliberately no blue double tick and no "read" state: a LoRa mesh cannot prove either. A room broadcast
/// stops at "heard by the mesh", which only means some node repeated it. Every state carries a spoken label as well as
/// a colour, so the meaning never depends on colour alone.
struct StatusTick: View {
    let status: MessageStatus
    @ScaledMetric private var size: CGFloat

    init(_ status: MessageStatus, size: CGFloat = 14) {
        self.status = status
        _size = ScaledMetric(wrappedValue: size, relativeTo: .caption2)
    }

    var body: some View {
        if let glyph = status.glyph {
            TickShape(glyph: glyph)
                .stroke(
                    status.tint,
                    style: StrokeStyle(
                        lineWidth: size * glyph.lineWidth / 24,
                        lineCap: .round, lineJoin: .round)
                )
                .frame(width: size, height: size)
                // Ticks and clocks never mirror in right-to-left languages.
                .environment(\.layoutDirection, .leftToRight)
                .accessibilityElement()
                .accessibilityLabel(Text(status.label ?? ""))
        }
    }
}

extension MessageStatus {
    var tint: Color {
        switch self {
        case .reachedMesh, .delivered: FirepitColors.live
        case .failed: FirepitColors.danger
        default: FirepitColors.textSecondary
        }
    }

    /// Nil while we are holding a message of our own: there is nothing to report.
    var label: LocalizedStringResource? {
        switch self {
        case .queued: "Sending"
        case .sentToNode, .unknown: "Sent to your node"
        case .reachedMesh: "Heard by at least one node"
        case .delivered: "Delivered to their node"
        case .unheard: "No node heard this"
        case .failed: "Failed, tap to retry"
        case .received: nil
        }
    }

    fileprivate var glyph: TickShape.Glyph? {
        switch self {
        case .queued: .pending
        case .sentToNode, .unknown, .reachedMesh, .unheard: .tick
        case .delivered: .tickDouble
        case .failed: .warning
        case .received: nil
        }
    }
}

/// Stroke glyphs for the ticks, drawn from the same 24-unit path data as Android's ic_tick, ic_tick_double,
/// ic_pending and ic_warning so both platforms show identical ticks.
private nonisolated struct TickShape: Shape {
    enum Glyph {
        case pending, tick, tickDouble, warning

        var lineWidth: CGFloat { 2 }
    }

    let glyph: Glyph

    func path(in rect: CGRect) -> Path {
        let unit = min(rect.width, rect.height) / 24
        func point(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
            CGPoint(x: rect.minX + x * unit, y: rect.minY + y * unit)
        }
        var path = Path()
        switch glyph {
        case .pending:
            path.addEllipse(in: CGRect(origin: point(4, 4), size: CGSize(width: 16 * unit, height: 16 * unit)))
            path.addLines([point(12, 7.5), point(12, 12), point(15, 13.8)])
        case .tick:
            path.addLines([point(5, 12.5), point(9.5, 17), point(19, 7.5)])
        case .tickDouble:
            path.addLines([point(2.5, 12.5), point(7, 17), point(16.5, 7.5)])
            path.addLines([point(11, 12.9), point(13.5, 15.4), point(22, 6.9)])
        case .warning:
            path.addLines([point(12, 4), point(21, 19.5), point(3, 19.5)])
            path.closeSubpath()
            path.addLines([point(12, 10), point(12, 14.2)])
            path.addLines([point(12, 16.8), point(12, 16.81)])
        }
        return path
    }
}

#Preview("Ticks") {
    VStack(alignment: .leading, spacing: 12) {
        ForEach(MessageStatus.allCases, id: \.self) { status in
            HStack {
                StatusTick(status, size: 20)
                Text(status.label ?? "Received")
            }
        }
    }
    .padding()
}
