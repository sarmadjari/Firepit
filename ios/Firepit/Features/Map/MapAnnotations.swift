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
    private var markerId: Int32?
    private var live: Bool?
    private var age: Int64?

    override init(reuseIdentifier: String?) {
        super.init(reuseIdentifier: reuseIdentifier)
        frame = CGRect(x: 0, y: 0, width: 112, height: 124)
        centerOffset = CGVector(dx: 0, dy: -28)
        imageView.contentMode = .center
        imageView.frame = bounds
        addSubview(imageView)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    override func prepareForReuse() {
        super.prepareForReuse()
        markerId = nil
        live = nil
        age = nil
    }

    func configure(marker: MapMarker) {
        guard markerId != marker.id || live != marker.isLive || age != marker.fixAgeMinutes else { return }
        markerId = marker.id
        live = marker.isLive
        age = marker.fixAgeMinutes
        let scheme: ColorScheme = traitCollection.userInterfaceStyle == .dark ? .dark : .light
        let view = MapMarkerArtwork(marker: marker)
            .environment(\.colorScheme, scheme)
            .frame(width: 112, height: 124)
        let renderer = ImageRenderer(content: view)
        renderer.scale = UIScreen.main.scale
        imageView.image = renderer.uiImage
    }
}

final class MapPinAnnotationView: MLNAnnotationView {
    private let imageView = UIImageView()

    override init(reuseIdentifier: String?) {
        super.init(reuseIdentifier: reuseIdentifier)
        frame = CGRect(x: 0, y: 0, width: 54, height: 66)
        centerOffset = CGVector(dx: 0, dy: -26)
        imageView.contentMode = .center
        imageView.frame = bounds
        addSubview(imageView)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    func configure() {
        if imageView.image == nil {
            let renderer = ImageRenderer(content: MapPinArtwork().frame(width: 54, height: 66))
            renderer.scale = UIScreen.main.scale
            imageView.image = renderer.uiImage
        }
    }
}

struct MapMarkerArtwork: View {
    let marker: MapMarker

    var body: some View {
        VStack(spacing: 6) {
            ZStack(alignment: .top) {
                Circle()
                    .fill(IdentityColors.color(for: marker.node.nodeNum, slot: marker.colourSlot))
                    .opacity(marker.isApproximate ? 0.62 : 1)
                    .frame(width: 64, height: 64)
                    .overlay {
                        Circle().strokeBorder(ringColor, lineWidth: marker.isSelf ? 6 : 4)
                    }
                if let course = marker.course {
                    Image(systemName: "triangle.fill")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(marker.isLive ? FirepitColors.live : FirepitColors.stale)
                        .offset(y: -9)
                        .rotationEffect(.degrees(course))
                }
                markerSymbol
                    .frame(width: 64, height: 64)
            }
            Text(verbatim: MapWords.markerLabel(marker))
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(marker.isLive ? FirepitColors.textPrimary : FirepitColors.textSecondary)
                .lineLimit(1)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(FirepitColors.surface2, in: .rect(cornerRadius: 8))
                .overlay { RoundedRectangle(cornerRadius: 8).strokeBorder(FirepitColors.outline) }
        }
        .padding(.top, 10)
    }

    @ViewBuilder private var markerSymbol: some View {
        if marker.role == .base || marker.role == .router {
            Image(icon: marker.role == .router ? .roleRouter : .roleBase)
                .font(.system(size: 29, weight: .semibold))
                .foregroundStyle(IdentityColors.onColor(for: marker.node.nodeNum, slot: marker.colourSlot))
        } else {
            Text(verbatim: marker.tag.uppercased())
                .font(.system(size: marker.tag.count > 2 ? 19 : 24, weight: .bold))
                .foregroundStyle(IdentityColors.onColor(for: marker.node.nodeNum, slot: marker.colourSlot))
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .padding(6)
        }
    }

    private var ringColor: Color {
        if marker.isSelf {
            Color(hex: 0x2563EB)
        } else if marker.isLive {
            FirepitColors.live
        } else {
            FirepitColors.stale
        }
    }
}

struct MapPinArtwork: View {
    var body: some View {
        Image(icon: .pin)
            .font(.system(size: 40, weight: .bold))
            .foregroundStyle(FirepitColors.warn)
            .shadow(color: FirepitColors.shadow, radius: 4, y: 2)
            .accessibilityHidden(true)
    }
}

func coordinateText(_ pin: MapPin) -> String {
    let latitude = pin.latitude.formatted(.number.precision(.fractionLength(5)))
    let longitude = pin.longitude.formatted(.number.precision(.fractionLength(5)))
    return "\(latitude), \(longitude)"
}
