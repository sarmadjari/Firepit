import FirepitModel
import FirepitProtocol
import MapLibre
import SwiftUI
import UIKit

final class MapNodeAnnotation: NSObject, MLNAnnotation {
    let marker: MapMarker
    dynamic var coordinate: CLLocationCoordinate2D
    var title: String? { marker.name }
    var subtitle: String? { marker.fixAgeMinutes.map { MapWords.agePhrase(minutes: $0) } }

    init?(marker: MapMarker) {
        guard let coordinate = marker.coordinate else { return nil }
        self.marker = marker
        self.coordinate = CLLocationCoordinate2D(latitude: coordinate.latitude, longitude: coordinate.longitude)
    }
}

final class MapPinAnnotation: NSObject, MLNAnnotation {
    let pin: MapPin
    dynamic var coordinate: CLLocationCoordinate2D
    var title: String? { pin.name.isEmpty ? String(localized: "Pin") : pin.name }
    var subtitle: String? { coordinateText(pin) }

    init(pin: MapPin) {
        self.pin = pin
        coordinate = CLLocationCoordinate2D(latitude: pin.latitude, longitude: pin.longitude)
    }
}

final class MapNodeAnnotationView: MLNAnnotationView {
    private let imageView = UIImageView()
    private var drawn: MapMarker?
    private var drawnStyle: UIUserInterfaceStyle?

    override init(reuseIdentifier: String?) {
        super.init(reuseIdentifier: reuseIdentifier)
        addSubview(imageView)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    override func prepareForReuse() {
        super.prepareForReuse()
        drawn = nil
    }

    func configure(marker: MapMarker) {
        let style = traitCollection.userInterfaceStyle
        guard drawn != marker || drawnStyle != style else { return }
        drawn = marker
        drawnStyle = style
        let renderer = ImageRenderer(
            content: MapMarkerArtwork(marker: marker).environment(\.colorScheme, style == .dark ? .dark : .light))
        renderer.scale = UIScreen.main.scale
        imageView.image = renderer.uiImage
        imageView.frame = CGRect(origin: .zero, size: imageView.image?.size ?? .zero)
        // The artwork keeps the disc at its centre, so the disc lands on the fix.
        frame = imageView.frame
        centerOffset = .zero
    }
}

final class MapPinAnnotationView: MLNAnnotationView {
    private let imageView = UIImageView()
    private var drawnName: String?

    override init(reuseIdentifier: String?) {
        super.init(reuseIdentifier: reuseIdentifier)
        addSubview(imageView)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    func configure(pin: MapPin) {
        guard imageView.image == nil || drawnName != pin.name else { return }
        drawnName = pin.name
        let renderer = ImageRenderer(content: MapPinArtwork(name: pin.name))
        renderer.scale = UIScreen.main.scale
        imageView.image = renderer.uiImage
        let size = imageView.image?.size ?? .zero
        imageView.frame = CGRect(origin: .zero, size: size)
        frame = imageView.frame
        // The tip marks the spot: move the view so the pin's bottom, not the middle of the drawing, is on it.
        centerOffset = CGVector(dx: 0, dy: size.height / 2 - MarkerSize.pin)
    }
}

/// Marker sizes in points, the same numbers Android draws in dp (UX spec §6.6), so a map looks alike on both.
nonisolated enum MarkerSize {
    static let disc: CGFloat = 34
    static let ring: CGFloat = 3
    static let selfRing: CGFloat = 4
    static let tag: CGFloat = 14
    /// A tag shrinks rather than leaves the disc: Meshtastic allows four characters.
    static let tagWidthShare: CGFloat = 0.78
    /// Leaves the disc's colour reading as a ring around a role symbol rather than a sliver.
    static let iconShare: CGFloat = 0.62
    static let label: CGFloat = 12
    static let pillPadX: CGFloat = 6
    static let pillPadY: CGFloat = 2
    static let pillCorner: CGFloat = 6
    static let pillGap: CGFloat = 3
    static let arrowWidth: CGFloat = 10
    static let arrowHeight: CGFloat = 8
    static let arrowGap: CGFloat = 2
    static let pin: CGFloat = 28
    static let pinLabel: CGFloat = 11
    /// A truncated fix describes an area, so the disc is softened to say so.
    static let approximateOpacity = 0.6
}

/// A tag disc in the identity colour, ringed while it is live, with the name on a pill beneath it.
struct MapMarkerArtwork: View {
    let marker: MapMarker

    var body: some View {
        VStack(spacing: MarkerSize.pillGap) {
            // A twin of the pill above the disc, so the disc sits at the centre of the image and on the fix.
            label.hidden()
            disc
            label
        }
    }

    private var disc: some View {
        let size = MarkerSize.disc
        let ink = IdentityColors.onColor(for: marker.node.nodeNum, slot: marker.colourSlot)
        return ZStack {
            Circle()
                .fill(IdentityColors.color(for: marker.node.nodeNum, slot: marker.colourSlot))
                .opacity(marker.isApproximate ? MarkerSize.approximateOpacity : 1)
            Circle().strokeBorder(ringColor, lineWidth: marker.isSelf ? MarkerSize.selfRing : MarkerSize.ring)
            // Which way they are going, when they are actually going somewhere.
            if let course = marker.course {
                CourseArrow()
                    .fill(marker.isLive ? FirepitColors.live : FirepitColors.stale)
                    .rotationEffect(.degrees(course))
            }
            if marker.role == .base || marker.role == .router {
                Image(icon: marker.role == .router ? .roleRouter : .roleBase)
                    .resizable()
                    .scaledToFit()
                    .foregroundStyle(ink)
                    .frame(width: size * MarkerSize.iconShare, height: size * MarkerSize.iconShare)
            } else {
                Text(verbatim: marker.tag.uppercased())
                    .font(.system(size: MarkerSize.tag, weight: .bold))
                    .foregroundStyle(ink)
                    .lineLimit(1)
                    .minimumScaleFactor(0.4)
                    .frame(width: size * MarkerSize.tagWidthShare)
            }
        }
        .frame(width: size, height: size)
    }

    private var label: some View {
        Text(verbatim: MapWords.markerLabel(marker))
            .font(.system(size: MarkerSize.label, weight: .semibold))
            .foregroundStyle(marker.isLive ? FirepitColors.textPrimary : FirepitColors.textSecondary)
            .lineLimit(1)
            .padding(.horizontal, MarkerSize.pillPadX)
            .padding(.vertical, MarkerSize.pillPadY)
            .background(FirepitColors.surface2, in: .rect(cornerRadius: MarkerSize.pillCorner))
            .overlay { RoundedRectangle(cornerRadius: MarkerSize.pillCorner).strokeBorder(FirepitColors.outline) }
    }

    private var ringColor: Color {
        if marker.isSelf {
            FirepitColors.mapSelf
        } else if marker.isLive {
            FirepitColors.live
        } else {
            FirepitColors.stale
        }
    }
}

/// The heading arrow above a disc, pointing up before it is rotated to the course.
private struct CourseArrow: Shape {
    nonisolated func path(in rect: CGRect) -> Path {
        let base = rect.minY - MarkerSize.arrowGap
        var path = Path()
        path.move(to: CGPoint(x: rect.midX, y: base - MarkerSize.arrowHeight))
        path.addLine(to: CGPoint(x: rect.midX - MarkerSize.arrowWidth / 2, y: base))
        path.addLine(to: CGPoint(x: rect.midX + MarkerSize.arrowWidth / 2, y: base))
        path.closeSubpath()
        return path
    }
}

/// A teardrop in the pin colour, with the pin's name under its tip as Android shows it.
struct MapPinArtwork: View {
    var name = ""

    var body: some View {
        VStack(spacing: 2) {
            MapPinShape()
                .fill(FirepitColors.mapPin)
                .overlay { MapPinHole().fill(.white) }
                .frame(width: MarkerSize.pin, height: MarkerSize.pin)
            if !name.isEmpty {
                Text(verbatim: name)
                    .font(.system(size: MarkerSize.pinLabel))
                    .foregroundStyle(FirepitColors.mapLabel)
                    .lineLimit(1)
                    .shadow(color: .white, radius: 1)
                    .shadow(color: .white, radius: 1)
            }
        }
        .accessibilityHidden(true)
    }
}

/// The teardrop, in the same proportions as Android's pin bitmap: a round head a third of the box, and a tail to
/// the bottom edge.
struct MapPinShape: Shape {
    nonisolated func path(in rect: CGRect) -> Path {
        let size = min(rect.width, rect.height)
        let radius = size / 3
        let centre = CGPoint(x: rect.midX, y: rect.minY + radius + size / 18)
        var path = Path()
        path.addEllipse(in: CGRect(x: centre.x - radius, y: centre.y - radius, width: radius * 2, height: radius * 2))
        path.move(to: CGPoint(x: centre.x - radius * 0.6, y: rect.minY + radius + size / 6))
        path.addLine(to: CGPoint(x: centre.x, y: rect.minY + size - size / 18))
        path.addLine(to: CGPoint(x: centre.x + radius * 0.6, y: rect.minY + radius + size / 6))
        path.closeSubpath()
        return path
    }
}

private struct MapPinHole: Shape {
    nonisolated func path(in rect: CGRect) -> Path {
        let size = min(rect.width, rect.height)
        let radius = size / 3
        let centre = CGPoint(x: rect.midX, y: rect.minY + radius + size / 18)
        let hole = radius * 0.38
        return Path(ellipseIn: CGRect(x: centre.x - hole, y: centre.y - hole, width: hole * 2, height: hole * 2))
    }
}

func coordinateText(_ pin: MapPin) -> String {
    let latitude = pin.latitude.formatted(.number.precision(.fractionLength(5)))
    let longitude = pin.longitude.formatted(.number.precision(.fractionLength(5)))
    return "\(latitude), \(longitude)"
}
