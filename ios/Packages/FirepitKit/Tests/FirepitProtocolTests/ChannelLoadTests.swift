import Testing

@testable import FirepitProtocol

@Suite struct ChannelLoadTests {
    @Test func absentTelemetryIsNotAReading() {
        #expect(ChannelLoad.of(utilizationPercent: nil) == nil)
    }

    @Test func quietChannelIsClear() {
        #expect(ChannelLoad.of(utilizationPercent: 0) == .clear)
        #expect(ChannelLoad.of(utilizationPercent: 24.9) == .clear)
    }

    @Test func busyBeginsWhereTheFirmwareThrottlesTelemetry() {
        #expect(ChannelLoad.of(utilizationPercent: 25) == .busy)
        #expect(ChannelLoad.of(utilizationPercent: 49.9) == .busy)
    }

    @Test func halfTheAirtimeIsCongested() {
        #expect(ChannelLoad.of(utilizationPercent: 50) == .congested)
        #expect(ChannelLoad.of(utilizationPercent: 100) == .congested)
    }
}
