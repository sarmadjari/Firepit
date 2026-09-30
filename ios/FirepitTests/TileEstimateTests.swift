import Foundation
import Testing

@testable import Firepit

/// Port of android/app/src/test/…/map/TileEstimateTest.kt.
@Suite("Tile estimate")
struct TileEstimateTests {
    @Test func theWholeWorldIsOneTileAtZoomZero() {
        let tiles = TileEstimate.tileCount(north: 85.0, south: -85.0, east: 180.0, west: -180.0, minZoom: 0, maxZoom: 0)

        #expect(tiles == 1)
    }

    @Test func aFinerZoomCostsMoreButNeverMoreThanFourTimesPlusItsEdges() {
        func count(_ zoom: Int) -> Int64 {
            TileEstimate.tileCount(north: 60.0, south: 0.0, east: 0.0, west: -90.0, minZoom: zoom, maxZoom: zoom)
        }

        let z4 = count(4)
        let z5 = count(5)

        // Not exactly 4x: the grids do not align, so a bounding box picks up partial tiles along each edge at every
        // level.
        #expect(z5 > z4, "a finer zoom must cost more, was \(z4) then \(z5)")
        #expect(z5 <= z4 * 4, "growth should stay near 4x, was \(z4) then \(z5)")
    }

    @Test func aZoomRangeSumsEveryLevel() {
        let single = TileEstimate.tileCount(north: 50.0, south: 49.0, east: 1.0, west: 0.0, minZoom: 12, maxZoom: 12)
        let range = TileEstimate.tileCount(north: 50.0, south: 49.0, east: 1.0, west: 0.0, minZoom: 12, maxZoom: 13)

        #expect(range > single, "a wider range must include more tiles")
    }

    @Test func anInvertedZoomRangeYieldsNothingRatherThanANegativeCount() {
        #expect(TileEstimate.tileCount(north: 50.0, south: 49.0, east: 1.0, west: 0.0, minZoom: 15, maxZoom: 8) == 0)
    }

    @Test func latitudesBeyondTheMercatorLimitAreClampedNotWrapped() {
        // The poles are not representable; asking for them must not produce a nonsense index or a negative count.
        let tiles = TileEstimate.tileCount(north: 90.0, south: -90.0, east: 10.0, west: 0.0, minZoom: 6, maxZoom: 6)

        #expect((1...10_000).contains(tiles), "clamped poles should still yield a sane count, was \(tiles)")
    }

    @Test func aTinyAreaStillCostsAtLeastOneTilePerZoom() {
        let tiles = TileEstimate.tileCount(
            north: 25.2049, south: 25.2048, east: 55.2709, west: 55.2708,
            minZoom: 8, maxZoom: 15)

        #expect(tiles == 8, "eight zoom levels, one tile each")
    }

    @Test func sizeIsDescribedInHumanTerms() {
        let english = Locale(identifier: "en_GB")
        #expect(TileEstimate.describe(10, locale: english) == "under 1 MB")
        #expect(TileEstimate.describe(100, locale: english) == "about 4.5 MB")
        #expect(TileEstimate.describe(100_000, locale: english) == "about 4,500 MB")
    }

    @Test func aBoxWidensTowardsThePolesButStaysOnTheMap() {
        let equator = boxAround(latitude: 0, longitude: 0, radiusKm: 10)
        let north = boxAround(latitude: 60, longitude: 0, radiusKm: 10)
        let pole = boxAround(latitude: 89, longitude: 179.9, radiusKm: 10)

        #expect(north.east - north.west > equator.east - equator.west)
        #expect(pole.north <= 85.05112878)
        #expect(pole.east <= 180)
    }
}
