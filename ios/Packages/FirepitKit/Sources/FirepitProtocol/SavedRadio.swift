/// What a radio is for.
///
/// The distinction is the user's, not the mesh's: Meshtastic has its own device
/// roles, but which radio is yours is a fact about the person carrying it.
public enum NodeRole: CaseIterable, Sendable {
    /// The one in your pocket. Exactly one, and the app stays connected to it.
    case personal

    /// A radio left somewhere useful. Any number.
    case base

    /// A radio placed to extend range. Any number.
    case router

    public var label: String {
        switch self {
        case .personal: "Personal"
        case .base: "Base"
        case .router: "Router"
        }
    }

    public var name: String {
        switch self {
        case .personal: "PERSONAL"
        case .base: "BASE"
        case .router: "ROUTER"
        }
    }
}

/// How the phone reaches a device.
public enum DeviceTransport: CaseIterable, Sendable {
    case bluetooth
    case usb
    case network

    public var label: String {
        switch self {
        case .bluetooth: "Bluetooth"
        case .usb: "USB"
        case .network: "Wi-Fi"
        }
    }

    public var name: String {
        switch self {
        case .bluetooth: "BLUETOOTH"
        case .usb: "USB"
        case .network: "NETWORK"
        }
    }
}

/// A radio this phone knows about.
public struct SavedRadio: Hashable, Sendable {
    public var identifier: String
    public var name: String
    public var role: NodeRole
    public var transport: DeviceTransport
    public var nodeNum: Int32?
    public var onMap: Bool
    /// The radio's own public key, base64, pinned the first time it said who it is.
    public var publicKey: String?

    public init(
        identifier: String,
        name: String,
        role: NodeRole,
        transport: DeviceTransport = .bluetooth,
        nodeNum: Int32? = nil,
        onMap: Bool = true,
        publicKey: String? = nil
    ) {
        self.identifier = identifier
        self.name = name
        self.role = role
        self.transport = transport
        self.nodeNum = nodeNum
        self.onMap = onMap
        self.publicKey = publicKey
    }
}

/// The set of radios this phone administers.
///
/// Only one can be Personal, because only one can be the radio the app keeps a
/// standing connection to.
public enum SavedRadios {
    public static func personal(radios: [SavedRadio]) -> SavedRadio? {
        radios.first { radio in
            radio.role == .personal
        }
    }

    /// Adds or updates one radio, keeping Personal unique.
    public static func assign(radios: [SavedRadio], radio: SavedRadio) -> [SavedRadio] {
        let others = radios.filter { existing in
            existing.identifier != radio.identifier
        }
        let adjusted: [SavedRadio]
        if radio.role == .personal {
            adjusted = others.map { existing in
                if existing.role == .personal {
                    var demoted = existing
                    demoted.role = .base
                    return demoted
                }
                return existing
            }
        } else {
            adjusted = others
        }
        return (adjusted + [radio]).sorted { lhs, rhs in
            if (lhs.role == .personal) != (rhs.role == .personal) {
                return lhs.role == .personal
            }
            return lhs.name.lowercased() < rhs.name.lowercased()
        }
    }

    public static func forget(radios: [SavedRadio], identifier: String) -> [SavedRadio] {
        radios.filter { radio in
            radio.identifier != identifier
        }
    }

    /// Node numbers of radios deliberately kept off the map.
    public static func hiddenNodes(radios: [SavedRadio]) -> Set<Int32> {
        Set(
            radios.filter { radio in
                !radio.onMap
            }.compactMap { radio in
                radio.nodeNum
            })
    }
}
