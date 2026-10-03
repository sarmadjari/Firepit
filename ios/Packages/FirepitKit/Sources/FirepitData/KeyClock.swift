import Darwin
import FirepitModel
import Foundation

/// What time it is, as far as room keys are concerned. Android: `KeyTime`.
public protocol KeyTime: Sendable {
    /// The phone's clock. Members agree on hours by it, so sealing and opening follow it.
    func wallMillis() -> Int64

    /// How far old keys may be erased up to: never later than the phone's clock, nor than the time that has really
    /// passed since that clock was last seen keeping step.
    func eraseMillis() -> Int64

    /// The room has just shown the phone's clock is right: another member's message, opened fresh, sealed within an
    /// hour of it.
    func agreed()
}

extension KeyTime {
    public func agreed() {}
}

/// The last moment the phone's clock and the time really passed agreed.
public struct ClockAnchor: Sendable, Equatable {
    public let wallMillis: Int64
    public let monotonicMillis: Int64
    public let boot: String?

    public init(wallMillis: Int64, monotonicMillis: Int64, boot: String?) {
        self.wallMillis = wallMillis
        self.monotonicMillis = monotonicMillis
        self.boot = boot
    }
}

/// Where that moment is kept, so the app being restarted does not forget it.
public protocol ClockAnchors: Sendable {
    func load() -> ClockAnchor?
    func save(_ anchor: ClockAnchor)
}

/// Keeps a clock set wrong from destroying keys that are still needed.
///
/// Room keys are erased on the hour, so a phone whose clock jumps a day ahead — set by hand, or by a network with the
/// wrong time — would erase keys the rest of the room is still using, and could not read it again even once the clock
/// was put right. So keys are erased on real time: the phone's clock is only believed as far as a monotonic clock (one
/// nothing can set, which keeps counting while the phone sleeps) agrees time has passed since the two last kept step.
/// A clock set ahead costs nothing permanent; a clock set back only keeps keys a little longer.
///
/// The anchor outlives the app only where the phone says which boot it is in: without that, an anchor from before a
/// restart could pass for this boot's and hold erasing back for days. iPhones may not tell apps, and then a clock
/// changed while the app was not running is believed when it next starts. A clock the room keeps agreeing with is
/// taken as right however it got there, which is how a clock put right after the phone started is caught up with.
///
/// What is left: a phone that starts up with its clock already wrong has nothing to compare it with. Android:
/// `KeyClock`.
public final class KeyClock: KeyTime {
    /// How far the clock may differ from the time really passed and still be taken as keeping step.
    public static let inStepMillis: Int64 = 5 * 60_000

    private static let saveEveryMillis: Int64 = 10 * 60_000

    private struct State {
        var anchor: ClockAnchor?
        var saved: ClockAnchor?
    }

    private let wall: @Sendable () -> Int64
    private let monotonic: @Sendable () -> Int64
    private let boot: @Sendable () -> String?
    private let anchors: any ClockAnchors
    private let state = Mutex(State())

    public init(
        wall: @escaping @Sendable () -> Int64,
        monotonic: @escaping @Sendable () -> Int64,
        boot: @escaping @Sendable () -> String?,
        anchors: any ClockAnchors
    ) {
        self.wall = wall
        self.monotonic = monotonic
        self.boot = boot
        self.anchors = anchors
    }

    /// The phone's own clocks, with the anchor kept in `defaults`.
    public static func system(defaults: UserDefaults = .standard) -> KeyClock {
        KeyClock(
            wall: { Int64((Date().timeIntervalSince1970 * 1_000).rounded(.down)) },
            // Keeps counting while the phone sleeps, and nothing can set it.
            monotonic: { Int64(clock_gettime_nsec_np(CLOCK_MONOTONIC) / 1_000_000) },
            boot: { bootSession() },
            anchors: DefaultsAnchors(defaults: defaults)
        )
    }

    public func wallMillis() -> Int64 { wall() }

    public func eraseMillis() -> Int64 {
        state.withLock { state in
            let now = wall()
            let elapsed = monotonic()
            let bootId = boot()
            var known = state.anchor
            // Only with a boot id: without one, an anchor from before the phone restarted could pass for this boot's.
            if known == nil, bootId != nil, let loaded = anchors.load() {
                state.saved = loaded
                known = loaded
            }
            // Another boot, or a monotonic clock that went back, which only a restart does.
            guard let anchor = known, anchor.boot == bootId, elapsed >= anchor.monotonicMillis else {
                keepStep(&state, ClockAnchor(wallMillis: now, monotonicMillis: elapsed, boot: bootId))
                return now
            }
            let real = anchor.wallMillis + (elapsed - anchor.monotonicMillis)
            if abs(now - real) <= Self.inStepMillis {
                keepStep(&state, ClockAnchor(wallMillis: now, monotonicMillis: elapsed, boot: bootId))
                return now
            }
            state.anchor = anchor
            return min(now, real)
        }
    }

    public func agreed() {
        state.withLock { state in
            keepStep(&state, ClockAnchor(wallMillis: wall(), monotonicMillis: monotonic(), boot: boot()))
        }
    }

    private func keepStep(_ state: inout State, _ next: ClockAnchor) {
        state.anchor = next
        guard next.boot != nil else {
            return
        }
        // Written now and then, not on every message: the anchor only moves to absorb drift. At once when what was
        // written belongs to another boot.
        if let last = state.saved, last.boot == next.boot, next.monotonicMillis >= last.monotonicMillis,
            next.monotonicMillis - last.monotonicMillis < Self.saveEveryMillis
        {
            return
        }
        anchors.save(next)
        state.saved = next
    }
}

/// This boot's identity, so an anchor from before the phone restarted is not taken for this one's.
private func bootSession() -> String? {
    var size = 0
    guard sysctlbyname("kern.bootsessionuuid", nil, &size, nil, 0) == 0, size > 0 else {
        return nil
    }
    var buffer = [CChar](repeating: 0, count: size)
    guard sysctlbyname("kern.bootsessionuuid", &buffer, &size, nil, 0) == 0 else {
        return nil
    }
    return String(cString: buffer)
}

/// Where `KeyClock` keeps its anchor. Nothing in it is secret.
private final class DefaultsAnchors: ClockAnchors {
    private static let wall = "firepit.keyClock.wall"
    private static let monotonic = "firepit.keyClock.monotonic"
    private static let boot = "firepit.keyClock.boot"

    private let defaults: Mutex<UserDefaults>

    init(defaults: UserDefaults) {
        self.defaults = Mutex(defaults)
    }

    func load() -> ClockAnchor? {
        defaults.withLock { defaults in
            guard let wall = defaults.object(forKey: Self.wall) as? NSNumber,
                let monotonic = defaults.object(forKey: Self.monotonic) as? NSNumber
            else {
                return nil
            }
            return ClockAnchor(
                wallMillis: wall.int64Value, monotonicMillis: monotonic.int64Value,
                boot: defaults.string(forKey: Self.boot))
        }
    }

    func save(_ anchor: ClockAnchor) {
        defaults.withLock { defaults in
            defaults.set(NSNumber(value: anchor.wallMillis), forKey: Self.wall)
            defaults.set(NSNumber(value: anchor.monotonicMillis), forKey: Self.monotonic)
            defaults.set(anchor.boot, forKey: Self.boot)
        }
    }
}
