import Foundation

/// A north/south/east/west box, before it becomes a MapLibre type. Ported from android/app/…/map/TileEstimate.kt.
nonisolated struct GeoBox: Hashable, Sendable {
    let north: Double
    let south: Double
    let east: Double
    let west: Double
}

/// Web Mercator cannot represent the poles; tiles stop at this latitude.
private nonisolated let maxTileLatitude = 85.05112878

private nonisolated let kmPerDegree = 111.32

/// About 84° north or south. Past that the box stops widening rather than wrapping the globe.
private nonisolated let minCosine = 0.1

/// A square box `radiusKm` either side of a point.
///
/// Longitude degrees narrow towards the poles, so the east–west span is divided by the cosine of the latitude; without
/// that a box in Scandinavia comes out half as wide as one at the equator. Near the poles the cosine approaches zero
/// and the span would explode, so it is floored.
nonisolated func boxAround(latitude: Double, longitude: Double, radiusKm: Double) -> GeoBox {
    let latitudeSpan = radiusKm / kmPerDegree
    let shrink = max(cos(latitude * .pi / 180.0), minCosine)
    let longitudeSpan = radiusKm / (kmPerDegree * shrink)
    return GeoBox(
        north: min(latitude + latitudeSpan, maxTileLatitude),
        south: max(latitude - latitudeSpan, -maxTileLatitude),
        east: min(longitude + longitudeSpan, 180.0),
        west: max(longitude - longitudeSpan, -180.0)
    )
}

/// How big a download will be, before starting it.
///
/// The tile count is exact slippy-map arithmetic. The byte figure is an estimate and is presented as one, because
/// vector tile size varies with how much is in the area: open country compresses to almost nothing, a city centre
/// does not.
nonisolated enum TileEstimate {
    /// Rough average for an OpenFreeMap vector tile at street zooms. Deliberately on the high side so the number shown
    /// is a ceiling rather than a surprise.
    private static let averageTileBytes: Int64 = 45_000

    static func tileCount(
        north: Double,
        south: Double,
        east: Double,
        west: Double,
        minZoom: Int,
        maxZoom: Int
    ) -> Int64 {
        if minZoom > maxZoom { return 0 }

        var total: Int64 = 0
        for zoom in minZoom...maxZoom {
            let xMin = lonToTileX(west, zoom: zoom)
            let xMax = lonToTileX(east, zoom: zoom)
            // Tile y grows southward, so the north edge gives the smaller index.
            let yMin = latToTileY(north, zoom: zoom)
            let yMax = latToTileY(south, zoom: zoom)

            let wide = Int64(max(xMax - xMin + 1, 1))
            let tall = Int64(max(yMax - yMin + 1, 1))
            total += wide * tall
        }
        return total
    }

    static func estimatedBytes(_ tiles: Int64) -> Int64 { tiles * averageTileBytes }

    /// Short human form, e.g. "about 42 MB".
    static func describe(_ tiles: Int64, locale: Locale = .current) -> String {
        let megabytes = Double(estimatedBytes(tiles)) / 1_000_000.0
        if megabytes < 1 {
            return String(localized: "under 1 MB", locale: locale)
        }
        if megabytes < 10 {
            let figure = megabytes.formatted(.number.precision(.fractionLength(1)).locale(locale))
            return String(localized: "about \(figure) MB", locale: locale)
        }
        let figure = Int(megabytes).formatted(.number.locale(locale))
        return String(localized: "about \(figure) MB", locale: locale)
    }

    private static func lonToTileX(_ longitude: Double, zoom: Int) -> Int {
        let scale = 1 << zoom
        let normalised = (min(max(longitude, -180.0), 180.0) + 180.0) / 360.0
        return min(max(Int((normalised * Double(scale)).rounded(.down)), 0), scale - 1)
    }

    private static func latToTileY(_ latitude: Double, zoom: Int) -> Int {
        let scale = 1 << zoom
        let clamped = min(max(latitude, -maxTileLatitude), maxTileLatitude)
        let radians = clamped * .pi / 180.0
        let normalised = (1.0 - asinh(tan(radians)) / .pi) / 2.0
        return min(max(Int((normalised * Double(scale)).rounded(.down)), 0), scale - 1)
    }
}
