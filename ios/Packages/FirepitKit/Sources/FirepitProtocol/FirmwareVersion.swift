import Foundation

/// Parsed from `DeviceMetadata.firmware_version`, e.g. "2.7.26.54e0d8d" or
/// "2.8.1-dev". Used only for behaviour differences that have no capability
/// field of their own; anything with a field is gated on the field instead.
public struct FirmwareVersion: Comparable, CustomStringConvertible, Equatable, Hashable, Sendable {
    public let major: Int
    public let minor: Int
    public let patch: Int
    public let raw: String

    public init(major: Int, minor: Int, patch: Int, raw: String) {
        self.major = major
        self.minor = minor
        self.patch = patch
        self.raw = raw
    }

    public static func < (lhs: FirmwareVersion, rhs: FirmwareVersion) -> Bool {
        if lhs.major != rhs.major {
            return lhs.major < rhs.major
        }
        if lhs.minor != rhs.minor {
            return lhs.minor < rhs.minor
        }
        return lhs.patch < rhs.patch
    }

    public var description: String {
        raw
    }

    public static func parseOrNull(_ raw: String) -> FirmwareVersion? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let pattern = #"^(\d+)\.(\d+)(?:\.(\d+))?"#
        guard let regex = try? NSRegularExpression(pattern: pattern) else {
            return nil
        }
        let range = NSRange(trimmed.startIndex..<trimmed.endIndex, in: trimmed)
        guard let match = regex.firstMatch(in: trimmed, range: range) else {
            return nil
        }
        guard let majorRange = Range(match.range(at: 1), in: trimmed) else {
            return nil
        }
        guard let minorRange = Range(match.range(at: 2), in: trimmed) else {
            return nil
        }
        let patch: Int
        if let patchRange = Range(match.range(at: 3), in: trimmed) {
            patch = Int(trimmed[patchRange]) ?? 0
        } else {
            patch = 0
        }
        guard let major = Int(trimmed[majorRange]) else {
            return nil
        }
        guard let minor = Int(trimmed[minorRange]) else {
            return nil
        }
        return FirmwareVersion(major: major, minor: minor, patch: patch, raw: trimmed)
    }
}

/// What this radio can actually do. Gate features on these, never on a version
/// string, wherever the protobuf exposes a capability field.
public struct RadioCapabilities: Equatable, Hashable, Sendable {
    public let firmwareVersion: FirmwareVersion?
    /// X25519 public-key encryption for direct messages.
    public let supportsPki: Bool
    /// 2.8 XEdDSA broadcast signing — drives the "verified" badge and nothing else.
    public let supportsSigning: Bool
    public let minAppVersion: Int
    /// 2.8 only; 0 on 2.7.
    public let nodeDbCount: Int

    public init(
        firmwareVersion: FirmwareVersion?,
        supportsPki: Bool,
        supportsSigning: Bool,
        minAppVersion: Int,
        nodeDbCount: Int
    ) {
        self.firmwareVersion = firmwareVersion
        self.supportsPki = supportsPki
        self.supportsSigning = supportsSigning
        self.minAppVersion = minAppVersion
        self.nodeDbCount = nodeDbCount
    }

    /// Firepit's baseline. Below this the PhoneAPI shape we rely on is not guaranteed.
    public var isSupported: Bool {
        firmwareVersion == nil || firmwareVersion! >= Self.minimumFirmware
    }

    public static let minimumFirmware = FirmwareVersion(major: 2, minor: 7, patch: 0, raw: "2.7.0")
}
