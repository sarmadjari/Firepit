@preconcurrency import CoreBluetooth
import FirepitProtocol
import Foundation

/// The radio's GATT profile, from the firmware's `BluetoothCommon.h`. The same values appear in the official Android
/// and Apple clients.
public enum MeshtasticGatt {
    public static let serviceUUID = "6BA1B218-15A8-461F-9FA8-5DCAE273EAFD"
    /// Write one encoded `ToRadio`, with response.
    public static let toRadioUUID = "F75C76D2-129E-4DAD-A1DD-7866124401E7"
    /// Read one queued `FromRadio`; an empty read means the queue is drained.
    public static let fromRadioUUID = "2C55E69E-4993-11ED-B878-0242AC120002"
    /// Notifies with a packet counter whenever data is waiting.
    public static let fromNumUUID = "ED9DA18C-A800-4F66-A670-AA7547E34453"

    static var service: CBUUID { CBUUID(string: serviceUUID) }
    static var toRadio: CBUUID { CBUUID(string: toRadioUUID) }
    static var fromRadio: CBUUID { CBUUID(string: fromRadioUUID) }
    static var fromNum: CBUUID { CBUUID(string: fromNumUUID) }

    /// FromNum is a little-endian uint32 packet counter.
    static func fromNumValue(_ bytes: Data) -> Int {
        var value: UInt32 = 0
        for (shift, byte) in bytes.prefix(4).enumerated() {
            value |= UInt32(byte) << (8 * UInt32(shift))
        }
        return Int(Int32(bitPattern: value))
    }
}

/// A Meshtastic radio seen while scanning. Ported from android/core/transport/…/RadioScanner.kt.
///
/// `identifier` is the system's per-app peripheral id as a string — iOS never exposes a Bluetooth address — and is what
/// a saved radio is stored and reconnected by, exactly as Android uses the address.
public struct DiscoveredRadio: Sendable, Hashable, Identifiable {
    public var identifier: String
    public var name: String?
    public var rssi: Int

    public init(identifier: String, name: String?, rssi: Int) {
        self.identifier = identifier
        self.name = name
        self.rssi = rssi
    }

    public var id: String { identifier }
}

/// Whether the phone's Bluetooth can be used at all, in words the radio screens can act on.
public enum BluetoothAvailability: Sendable, Equatable {
    case unknown
    case poweredOn
    case poweredOff
    /// The person declined Bluetooth access for Firepit, or a profile forbids it.
    case unauthorized
    case unsupported
    case resetting

    init(_ state: CBManagerState) {
        switch state {
        case .poweredOn: self = .poweredOn
        case .poweredOff: self = .poweredOff
        case .unauthorized: self = .unauthorized
        case .unsupported: self = .unsupported
        case .resetting: self = .resetting
        default: self = .unknown
        }
    }
}

public enum BluetoothError: Error, Equatable, Sendable {
    case unavailable(BluetoothAvailability)
    /// The system does not know this radio any more (forgotten, or never seen on this phone).
    case unknownRadio
    case connectionFailed(String)
    case disconnected(String)
    /// Connected, but it does not offer the Meshtastic service: not a Meshtastic radio, or still booting.
    case notAMeshtasticRadio
}
