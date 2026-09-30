/// A pin dropped on the map.
///
/// Travels sealed under the room's key, so only members see it and only a member can change it; `lockedTo` then narrows
/// editing to one of them. A member can still write anything, so a pin is a claim, not a fact.
public struct MapPin: Hashable, Sendable, Identifiable {
    public var id: Int32
    public var channel: Int
    public var latitudeI: Int32
    public var longitudeI: Int32
    public var name: String
    public var description: String
    /// Epoch seconds. Zero means it never expires.
    public var expire: Int64
    /// Node number allowed to edit, or 0 when anyone on the channel may.
    public var lockedTo: Int32
    public var icon: String?
    public var createdBy: Int32
    public var receivedAt: Int64
    /// The room it belongs to, which outlives the slot the room sits in.
    public var roomId: Int32

    public init(
        id: Int32,
        channel: Int,
        latitudeI: Int32,
        longitudeI: Int32,
        name: String,
        description: String = "",
        expire: Int64 = 0,
        lockedTo: Int32 = 0,
        icon: String? = nil,
        createdBy: Int32,
        receivedAt: Int64,
        roomId: Int32 = 0
    ) {
        self.id = id
        self.channel = channel
        self.latitudeI = latitudeI
        self.longitudeI = longitudeI
        self.name = name
        self.description = description
        self.expire = expire
        self.lockedTo = lockedTo
        self.icon = icon
        self.createdBy = createdBy
        self.receivedAt = receivedAt
        self.roomId = roomId
    }

    public var latitude: Double { Double(latitudeI) * 1e-7 }

    public var longitude: Double { Double(longitudeI) * 1e-7 }

    public func isExpired(nowMillis: Int64 = currentEpochMillis()) -> Bool {
        expire >= 1 && expire < nowMillis / 1000
    }

    public func canEdit(myNodeNum: Int32?) -> Bool { lockedTo == 0 || lockedTo == myNodeNum }
}
