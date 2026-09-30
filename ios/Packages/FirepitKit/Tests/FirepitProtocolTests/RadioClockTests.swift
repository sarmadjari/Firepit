import Testing

@testable import FirepitProtocol

@Suite struct RadioClockTests {
    private let now: Int64 = 1_757_700_000_000

    @Test func aRadioThatWasNeverToldTheTimeHasNothingToSay() {
        #expect(RadioClock.onPhoneClock(radioSeconds: 0, skewMillis: 0, now: now) == nil)
        #expect(RadioClock.ifPlausible(claimSeconds: 0, now: now) == nil)
    }

    @Test func aRadioRunningBehindIsReadForwardOntoThePhonesClock() {
        let behind: Int64 = -2 * 24 * 60 * 60 * 1_000
        let stamped = Int32((now + behind) / 1_000)
        #expect(RadioClock.onPhoneClock(radioSeconds: stamped, skewMillis: behind, now: now) == now)
    }

    @Test func aRadioRunningAheadIsReadBackOntoThePhonesClock() {
        let ahead: Int64 = 90 * 60 * 1_000
        let stamped = Int32((now + ahead) / 1_000)
        #expect(RadioClock.onPhoneClock(radioSeconds: stamped, skewMillis: ahead, now: now) == now)
    }

    @Test func anUnmeasuredSkewLeavesTheStampAsItCame() {
        let heard = now - 60_000
        let stamped = Int32(heard / 1_000)
        #expect(RadioClock.onPhoneClock(radioSeconds: stamped, skewMillis: nil, now: now) == heard)
    }

    @Test func aCorrectedStampNeverLandsInTheFuture() {
        let stamped = Int32((now + 60_000) / 1_000)
        #expect(RadioClock.onPhoneClock(radioSeconds: stamped, skewMillis: 0, now: now) == now)
    }

    @Test func aSendersStampIsKeptWhileItCouldBeTrue() {
        let taken = now - 10 * 60 * 1_000
        #expect(RadioClock.ifPlausible(claimSeconds: Int32(taken / 1_000), now: now) == taken)
    }

    @Test func aSenderClaimingTheFutureIsRefused() {
        let ahead = now + RadioClock.claimAheadMs + 60_000
        #expect(RadioClock.ifPlausible(claimSeconds: Int32(ahead / 1_000), now: now) == nil)
    }

    @Test func aSenderStuckNearTheEpochIsRefused() {
        #expect(RadioClock.ifPlausible(claimSeconds: 1, now: now) == nil)
    }

    @Test func aSenderOlderThanAnythingKeptIsRefused() {
        let ancient = now - RadioClock.claimStaleMs - 60_000
        #expect(RadioClock.ifPlausible(claimSeconds: Int32(ancient / 1_000), now: now) == nil)
    }

    @Test func wireSecondsBecomeMillis() {
        let seconds = Int32(now / 1_000)
        #expect(RadioClock.ifPlausible(claimSeconds: seconds, now: now) == Int64(seconds) * 1_000)
    }
}
