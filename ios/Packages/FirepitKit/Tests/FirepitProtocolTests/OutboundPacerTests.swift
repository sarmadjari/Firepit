import FirepitProtos
import Foundation
import Testing

@testable import FirepitProtocol

/// Test-only lock box for deterministic virtual time shared by @Sendable closures.
/// The lock serializes every access, so using unchecked Sendable here is confined to tests.
private final class VirtualClock: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Int64 = 0
    private var wakeTimes: [Int64] = []

    func now() -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    func advance(_ millis: Int64) {
        lock.lock()
        value += millis
        wakeTimes.append(value)
        lock.unlock()
    }

    func popWakeOrNow() -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        if wakeTimes.isEmpty {
            return value
        }
        return wakeTimes.removeFirst()
    }
}

@Suite struct OutboundPacerTests {
    @Test func firstPacketOnAPortGoesImmediately() async {
        let clock = VirtualClock()
        let pacer = OutboundPacer(nowMillis: { clock.now() }, sleepMillis: { clock.advance($0) })

        await pacer.awaitSlot(portNum: .textMessageApp)

        #expect(0 == clock.now())
    }

    @Test func textMessagesAreSpacedTwoSecondsApart() async {
        let clock = VirtualClock()
        let pacer = OutboundPacer(nowMillis: { clock.now() }, sleepMillis: { clock.advance($0) })

        await pacer.awaitSlot(portNum: .textMessageApp)
        await pacer.awaitSlot(portNum: .textMessageApp)

        #expect(2_000 == clock.now())
    }

    @Test func positionsAreSpacedTenSecondsApart() async {
        let clock = VirtualClock()
        let pacer = OutboundPacer(nowMillis: { clock.now() }, sleepMillis: { clock.advance($0) })

        await pacer.awaitSlot(portNum: .positionApp)
        await pacer.awaitSlot(portNum: .positionApp)

        #expect(10_000 == clock.now())
    }

    @Test func limitsArePerPortnumSoAPositionNeverDelaysAText() async {
        let clock = VirtualClock()
        let pacer = OutboundPacer(nowMillis: { clock.now() }, sleepMillis: { clock.advance($0) })

        await pacer.awaitSlot(portNum: .positionApp)
        await pacer.awaitSlot(portNum: .textMessageApp)

        #expect(0 == clock.now())
    }

    @Test func unlimitedPortnumsNeverWait() async {
        let clock = VirtualClock()
        let pacer = OutboundPacer(nowMillis: { clock.now() }, sleepMillis: { clock.advance($0) })

        for _ in 0..<5 {
            await pacer.awaitSlot(portNum: .adminApp)
        }
        for _ in 0..<5 {
            await pacer.awaitSlot(portNum: .nodeinfoApp)
        }

        #expect(0 == clock.now())
    }

    @Test func concurrentSendersQueueBehindEachOtherInsteadOfFiringTogether() async {
        let clock = VirtualClock()
        let pacer = OutboundPacer(nowMillis: { clock.now() }, sleepMillis: { clock.advance($0) })
        let sentAt = LockedArray<Int64>()

        await withTaskGroup(of: Void.self) { group in
            for _ in 0..<3 {
                group.addTask {
                    await pacer.awaitSlot(portNum: .textMessageApp)
                    sentAt.append(clock.popWakeOrNow())
                }
            }
        }

        #expect([0, 2_000, 4_000] == sentAt.values().sorted())
    }

    @Test func tracerouteIsSpacedThirtySecondsApart() async {
        let clock = VirtualClock()
        let pacer = OutboundPacer(nowMillis: { clock.now() }, sleepMillis: { clock.advance($0) })

        await pacer.awaitSlot(portNum: .tracerouteApp)
        await pacer.awaitSlot(portNum: .tracerouteApp)

        #expect(30_000 == clock.now())
    }
}

/// Test-only locked array for concurrently recording send times.
private final class LockedArray<Element>: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: [Element] = []

    func append(_ value: Element) {
        lock.lock()
        storage.append(value)
        lock.unlock()
    }

    func values() -> [Element] {
        lock.lock()
        defer { lock.unlock() }
        return storage
    }
}
