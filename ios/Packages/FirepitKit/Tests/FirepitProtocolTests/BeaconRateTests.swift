import Testing

@testable import FirepitProtocol

@Suite struct BeaconRateTests {
    @Test func readsARateTheRadioIsAlreadySetTo() {
        #expect(BeaconRate.of(seconds: 900) == .steady)
        #expect(BeaconRate.of(seconds: 3_600) == .hourly)
    }

    @Test func zeroMeansTheFirmwareDefaultNotNever() {
        #expect(BeaconRate.of(seconds: BeaconRate.firmwareDefaultSeconds) == BeaconRate.of(seconds: 0))
    }

    @Test func anIntervalFirepitDoesNotOfferIsLeftAloneRatherThanRounded() {
        #expect(BeaconRate.of(seconds: 437) == nil)
    }

    @Test func aRadioThatHasSaidNothingYetHasNoRateToShow() {
        #expect(BeaconRate.of(seconds: nil) == nil)
    }

    @Test func everyRateIsADistinctInterval() {
        let seconds = BeaconRate.allCases.map { $0.seconds }
        #expect(seconds.count == Set(seconds).count)
    }
}
