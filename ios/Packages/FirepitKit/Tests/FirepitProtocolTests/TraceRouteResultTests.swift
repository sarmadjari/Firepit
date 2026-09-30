import Testing

@testable import FirepitProtocol

@Suite struct TraceRouteResultTests {
    @Test func anEmptyRouteMeansTheTargetAnsweredDirectly() {
        let result = TraceRouteResult.from(target: 42, route: [], snrTowards: [], routeBack: [], snrBack: [])
        #expect(result.isDirect)
        #expect(result.hopsOut == 1)
    }

    @Test func snrIsDecodedFromQuarterDecibels() throws {
        let result = TraceRouteResult.from(target: 42, route: [7], snrTowards: [26], routeBack: [], snrBack: [])
        let snr = try #require(result.towards.single?.snr)
        #expect(abs(snr - 6.5) < 0.001)
    }

    @Test func aRelayedRouteCountsTheRelayAndTheTarget() {
        let result = TraceRouteResult.from(
            target: 42, route: [7, 9], snrTowards: [20, 12], routeBack: [], snrBack: [])
        #expect(!result.isDirect)
        #expect(result.hopsOut == 3)
    }

    @Test func aMissingSnrIsAbsentRatherThanZero() throws {
        let result = TraceRouteResult.from(target: 42, route: [7, 9], snrTowards: [20], routeBack: [], snrBack: [])
        let snr = try #require(result.towards[0].snr)
        #expect(abs(snr - 5.0) < 0.001)
        #expect(result.towards[1].snr == nil)
    }

    @Test func theFirmwaresUnknownMarkerIsNotShownAsAReading() throws {
        let result = TraceRouteResult.from(target: 42, route: [7], snrTowards: [-128], routeBack: [], snrBack: [])
        let hop = try #require(result.towards.single)
        #expect(hop.snr == nil)
    }

    @Test func extraSnrValuesWithoutAMatchingHopAreIgnored() {
        let result = TraceRouteResult.from(
            target: 42, route: [7], snrTowards: [20, 30, 40], routeBack: [], snrBack: [])
        #expect(result.towards.count == 1)
    }

    @Test func theReturnPathIsDecodedIndependentlyOfTheOutwardOne() throws {
        let result = TraceRouteResult.from(
            target: 42, route: [7], snrTowards: [20], routeBack: [9, 11], snrBack: [8, 4])
        #expect(result.back.map { $0.nodeNum } == [9, 11])
        let first = try #require(result.back[0].snr)
        let second = try #require(result.back[1].snr)
        #expect(abs(first - 2.0) < 0.001)
        #expect(abs(second - 1.0) < 0.001)
    }
}

extension Array {
    fileprivate var single: Element? {
        count == 1 ? self[0] : nil
    }
}
