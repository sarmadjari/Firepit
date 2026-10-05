import FirepitModel
import FirepitProtocol
import Foundation

public struct SharingDeadline: Sendable, Equatable {
    public var roomId: Int32
    public var choice: ShareDuration
    /// Nil means it runs until somebody turns it off.
    public var endsAt: Int64?
    public var radioSafetyNet: Bool
    public var safetyNetNodeNum: Int32?

    public init(
        roomId: Int32,
        choice: ShareDuration,
        endsAt: Int64?,
        radioSafetyNet: Bool = false,
        safetyNetNodeNum: Int32? = nil
    ) {
        self.roomId = roomId
        self.choice = choice
        self.endsAt = endsAt
        self.radioSafetyNet = radioSafetyNet
        self.safetyNetNodeNum = safetyNetNodeNum
    }

    public func hasPassed(nowMillis: Int64) -> Bool {
        endsAt != nil && nowMillis >= endsAt!
    }
}

/// Which room we share our position with, and when that stops, kept across restarts.
///
/// This is what sharing is: the phone sends a sealed position to this room while it is set and the radio is connected.
/// Kept on disk so a restart neither forgets a share somebody chose nor revives one whose time has passed.
public final class SharingStore: Sendable {
    private let defaults: Mutex<UserDefaults>
    public let deadline: CurrentValue<SharingDeadline?>

    public init(defaults: UserDefaults = .standard) {
        self.defaults = Mutex(defaults)
        deadline = CurrentValue(Self.read(from: defaults))
    }

    public func remember(
        roomId: Int32,
        choice: ShareDuration,
        nowMillis: Int64,
        radioSafetyNet: Bool = false,
        safetyNetNodeNum: Int32? = nil
    ) {
        let endsAt = choice.endsAt(nowMillis: nowMillis)
        defaults.withLock { defaults in
            defaults.set(Int(roomId), forKey: Self.roomKey)
            defaults.set(choice.name, forKey: Self.choiceKey)
            defaults.set(radioSafetyNet, forKey: Self.radioSafetyNetKey)
            if let safetyNetNodeNum {
                defaults.set(Int(safetyNetNodeNum), forKey: Self.radioNodeKey)
            } else {
                defaults.removeObject(forKey: Self.radioNodeKey)
            }
            defaults.removeObject(forKey: Self.radioPskKey)
            defaults.removeObject(forKey: Self.needsSilencingKey)
            if let endsAt {
                defaults.set(endsAt, forKey: Self.endsAtKey)
            } else {
                defaults.removeObject(forKey: Self.endsAtKey)
            }
        }
        deadline.set(defaults.withLock { Self.read(from: $0) })
    }

    public func clear() {
        defaults.withLock { defaults in
            defaults.removeObject(forKey: Self.roomKey)
            defaults.removeObject(forKey: Self.choiceKey)
            defaults.removeObject(forKey: Self.endsAtKey)
            defaults.removeObject(forKey: Self.radioSafetyNetKey)
            defaults.removeObject(forKey: Self.radioNodeKey)
            defaults.removeObject(forKey: Self.radioPskKey)
            defaults.removeObject(forKey: Self.needsSilencingKey)
        }
        deadline.set(nil)
    }

    private static func read(from defaults: UserDefaults) -> SharingDeadline? {
        let roomId = defaults.integer(forKey: roomKey)
        if roomId == 0 {
            return nil
        }
        let endsAt =
            defaults.object(forKey: endsAtKey) == nil
            ? nil
            : Int64(defaults.integer(forKey: endsAtKey))
        return SharingDeadline(
            roomId: Int32(roomId),
            choice: ShareDuration.named(name: defaults.string(forKey: choiceKey)),
            endsAt: endsAt,
            radioSafetyNet: defaults.bool(forKey: radioSafetyNetKey),
            safetyNetNodeNum: defaults.object(forKey: radioNodeKey) == nil ? nil : Int32(defaults.integer(forKey: radioNodeKey))
        )
    }

    private static let roomKey = "room_id"
    private static let choiceKey = "choice"
    private static let endsAtKey = "ends_at"
    private static let radioSafetyNetKey = "radio_safety_net"
    private static let radioNodeKey = "radio_node"
    private static let radioPskKey = "radio_psk"
    private static let needsSilencingKey = "needs_silencing"
}
