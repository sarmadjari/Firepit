import MapLibre
import UIKit

/// Greys out ground that is not downloaded. Ported from android/app/…/map/CoverageMask.kt.
///
/// A polygon covering the world with a hole punched for each saved area, so what remains in colour is exactly what
/// will still be there with no signal. Without this, going offline just makes tiles stop appearing, with nothing to say
/// why.
///
/// The holes are region bounds, not true tile coverage: a region downloaded to zoom 15 has nothing beyond it, so
/// zooming far in inside a clear area can still run out of detail.
@MainActor
struct CoverageMask {
    private static let sourceId = "coverage-mask-source"
    private static let layerId = "coverage-mask-layer"
    /// A neutral grey that reads as "absent" over both the light and the dark basemap.
    private static let maskColor = UIColor(red: 0x5B / 255.0, green: 0x5B / 255.0, blue: 0x5B / 255.0, alpha: 1)
    private static let maskOpacity = 0.62

    /// Web Mercator stops short of the poles, so the outer ring does too.
    private static let worldRing = [
        CLLocationCoordinate2D(latitude: -85, longitude: -180),
        CLLocationCoordinate2D(latitude: -85, longitude: 180),
        CLLocationCoordinate2D(latitude: 85, longitude: 180),
        CLLocationCoordinate2D(latitude: 85, longitude: -180),
        CLLocationCoordinate2D(latitude: -85, longitude: -180),
    ]

    func apply(style: MLNStyle, areas: [OfflineArea], enabled: Bool) {
        let bounds = areas.compactMap(\.bounds)
        if !enabled || bounds.isEmpty {
            remove(style: style)
            return
        }
        let holes = bounds.map { box in
            let ring = Self.ring(for: box)
            return MLNPolygon(coordinates: ring, count: UInt(ring.count))
        }
        let polygon = MLNPolygon(
            coordinates: Self.worldRing, count: UInt(Self.worldRing.count),
            interiorPolygons: holes)
        if let existing = style.source(withIdentifier: Self.sourceId) as? MLNShapeSource {
            existing.shape = polygon
            return
        }
        let source = MLNShapeSource(identifier: Self.sourceId, shape: polygon)
        style.addSource(source)
        let layer = MLNFillStyleLayer(identifier: Self.layerId, source: source)
        layer.fillColor = NSExpression(forConstantValue: Self.maskColor)
        layer.fillOpacity = NSExpression(forConstantValue: Self.maskOpacity)
        style.addLayer(layer)
    }

    func remove(style: MLNStyle) {
        if let layer = style.layer(withIdentifier: Self.layerId) {
            style.removeLayer(layer)
        }
        // The layer goes first, so the source is no longer in use when it is removed.
        if let source = style.source(withIdentifier: Self.sourceId) {
            style.removeSource(source)
        }
    }

    private static func ring(for box: GeoBox) -> [CLLocationCoordinate2D] {
        [
            CLLocationCoordinate2D(latitude: box.south, longitude: box.west),
            CLLocationCoordinate2D(latitude: box.south, longitude: box.east),
            CLLocationCoordinate2D(latitude: box.north, longitude: box.east),
            CLLocationCoordinate2D(latitude: box.north, longitude: box.west),
            CLLocationCoordinate2D(latitude: box.south, longitude: box.west),
        ]
    }
}
