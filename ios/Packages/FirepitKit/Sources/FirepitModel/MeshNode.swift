/// A node the radio has heard of.
///
/// Everything here except `nodeNum` arrives from the mesh and is therefore attacker-controlled: names are untrusted
/// display strings, not identifiers.
public struct MeshNode: Hashable, Sendable, Identifiable {
    public var nodeNum: Int32
    public var userId: String?
    public var longName: String?
    /// The 2-character tag shown in avatars and map markers.
    public var shortName: String?
    public var hwModel: String?
    public var role: String?
    /// Base64. Required before a direct message can be encrypted to this node.
    public var publicKey: String?
    /// Infrastructure nodes set this so they are not offered as chat targets.
    public var isUnmessagable: Bool
    public var lastHeard: Int64?
    public var snr: Float?
    public var rssi: Int?
    public var hopsAway: Int?
    /// 101 means USB powered, per the firmware's magic value.
    public var batteryLevel: Int?
    public var voltage: Float?
    public var channelUtilization: Float?
    public var airUtilTx: Float?
    public var isFavorite: Bool
    /// 1e-7 degrees, as the mesh carries it. Nil until the node reports a fix.
    public var latitudeI: Int32?
    public var longitudeI: Int32?
    public var altitude: Int?
    /// When the position was measured, which is not when we heard about it.
    public var positionTime: Int64?
    /// Bits the sender truncated to; below 32 the point is an area, not a place.
    public var positionPrecision: Int?
    /// km/h, absent when the radio did not report movement.
    public var groundSpeed: Int?
    /// True North course in hundredths of a degree.
    public var groundTrack: Int?

    public init(
        nodeNum: Int32,
        userId: String? = nil,
        longName: String? = nil,
        shortName: String? = nil,
        hwModel: String? = nil,
        role: String? = nil,
        publicKey: String? = nil,
        isUnmessagable: Bool = false,
        lastHeard: Int64? = nil,
        snr: Float? = nil,
        rssi: Int? = nil,
        hopsAway: Int? = nil,
        batteryLevel: Int? = nil,
        voltage: Float? = nil,
        channelUtilization: Float? = nil,
        airUtilTx: Float? = nil,
        isFavorite: Bool = false,
        latitudeI: Int32? = nil,
        longitudeI: Int32? = nil,
        altitude: Int? = nil,
        positionTime: Int64? = nil,
        positionPrecision: Int? = nil,
        groundSpeed: Int? = nil,
        groundTrack: Int? = nil
    ) {
        self.nodeNum = nodeNum
        self.userId = userId
        self.longName = longName
        self.shortName = shortName
        self.hwModel = hwModel
        self.role = role
        self.publicKey = publicKey
        self.isUnmessagable = isUnmessagable
        self.lastHeard = lastHeard
        self.snr = snr
        self.rssi = rssi
        self.hopsAway = hopsAway
        self.batteryLevel = batteryLevel
        self.voltage = voltage
        self.channelUtilization = channelUtilization
        self.airUtilTx = airUtilTx
        self.isFavorite = isFavorite
        self.latitudeI = latitudeI
        self.longitudeI = longitudeI
        self.altitude = altitude
        self.positionTime = positionTime
        self.positionPrecision = positionPrecision
        self.groundSpeed = groundSpeed
        self.groundTrack = groundTrack
    }

    public var id: Int32 { nodeNum }

    /// Display form used by every Meshtastic client.
    public var displayId: String { userId ?? MeshNode.formatNodeId(nodeNum) }

    public var displayName: String {
        if let longName, !longName.allSatisfy(\.isWhitespace) { return longName }
        return displayId
    }

    public var latitude: Double? { latitudeI.map { Double($0) * 1e-7 } }

    public var longitude: Double? { longitudeI.map { Double($0) * 1e-7 } }

    public var hasPosition: Bool { latitudeI != nil && longitudeI != nil }

    /// `!` and eight lowercase hex digits of the unsigned node number, e.g. `!a1b2c3d4` (Android's `"!%08x".format`).
    public static func formatNodeId(_ nodeNum: Int32) -> String {
        let hex = String(UInt32(bitPattern: nodeNum), radix: 16)
        return "!" + String(repeating: "0", count: max(0, 8 - hex.count)) + hex
    }
}
