@testable import Firepit
import FirepitModel
import FirepitProtocol
import Foundation
import Testing

/// The words the sharing controls use, ported from the rules in android/app/…/location/ShareLocationUi.kt.
@Suite("Location sharing summary")
struct SharingSummaryTests {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func millis(after interval: TimeInterval) -> Int64 {
        Int64((now.timeIntervalSince1970 + interval) * 1000)
    }

    @Test func hoursAndMinutesAreBothShown() {
        #expect(timeLeft(millis(after: 3 * 3600 + 12 * 60 + 30), now: now) == "3h 12m left")
    }

    @Test func underAnHourShowsMinutesOnly() {
        #expect(timeLeft(millis(after: 12 * 60 + 5), now: now) == "12m left")
    }

    @Test func theLastMinuteIsNamed() {
        #expect(timeLeft(millis(after: 30), now: now) == "less than a minute left")
    }

    @Test func aPassedDeadlineSaysItIsStopping() {
        #expect(timeLeft(millis(after: -1), now: now) == "stopping")
    }

    @Test func nothingSharedIsSaidOutright() {
        #expect(sharingSummary(SharingUiState(), now: now) == "Not shared with anyone")
    }

    @Test func aPausedShareSaysWhyAndNamesTheRoom() {
        let state = SharingUiState(connected: true, roomId: 42, roomName: "Camp", paused: true)
        #expect(sharingSummary(state, now: now) == """
            Paused: your phone is away from your radio, or it doesn't carry Camp. Resumes when it's back
            """)
    }

    @Test func aShareWithoutAnEndSaysSo() {
        let state = SharingUiState(connected: true, roomId: 42, roomName: "Camp", choice: .untilOff)
        #expect(sharingSummary(state, now: now) == "Shared with Camp until you turn it off")
    }

    @Test func aTimedShareCountsDown() {
        let state = SharingUiState(connected: true, roomId: 42, roomName: "Camp", endsAt: millis(after: 25 * 60))
        #expect(sharingSummary(state, now: now) == "Shared with Camp · 25m left")
    }

    @Test func aRoomTheRadioDoesNotCarryIsStillNamedGenerically() {
        let state = SharingUiState(connected: true, roomId: 42, endsAt: millis(after: 90))
        #expect(sharingSummary(state, now: now) == "Shared with a room · 1m left")
    }

    @Test func theDefaultLengthIsFourHours() {
        #expect(SharingUiState().choice == .fourHours)
    }
}
