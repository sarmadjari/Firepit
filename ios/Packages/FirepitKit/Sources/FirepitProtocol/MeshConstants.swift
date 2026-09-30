import Foundation

/// Wire-contract constants. Values are asserted against the vendored protobufs in `ProtocolContractTests`, so they
/// cannot silently drift from the firmware.
public enum MeshConstants {
    /// 0xFFFFFFFF as a signed uint32.
    public static let broadcastNodeNum: Int32 = -1

    /// Firmware default when `lora.hop_limit` is 0. Never raise it: it multiplies traffic mesh-wide.
    public static let defaultHopLimit = 3
    public static let maxHopLimit = 7

    /// `Constants.DATA_PAYLOAD_LEN` — the room available to `Data.payload`.
    public static let dataPayloadLen = 233

    /// PKI direct messages cost 12 bytes of tag and extra nonce.
    public static let pkcOverhead = 12

    /// A 2.8 XEdDSA signature costs 64 bytes plus 2 of field encoding.
    public static let xeddsaSignatureOverhead = 66

    /// Composer cap, matching the Apple client so messages render identically everywhere.
    public static let maxTextBytes = 200

    /// Past this, a 2.8 node sends the broadcast unsigned because the signature no longer fits.
    public static let signedBroadcastTextBudget = 165

    /// A single ToRadio/FromRadio message never exceeds this.
    public static let maxToFromRadioSize = 512

    /// `!` and eight lowercase hex digits, the form every Meshtastic client shows.
    public static func formatNodeId(_ nodeNum: Int32) -> String {
        let hex = String(UInt32(bitPattern: nodeNum), radix: 16)
        return "!" + String(repeating: "0", count: max(0, 8 - hex.count)) + hex
    }

    /// Trims `text` to at most `maxBytes` of UTF-8 without splitting a code point.
    ///
    /// The limit is in bytes but people type characters, and Arabic, emoji and accented Latin all cost more than one
    /// byte each. Cutting at a byte index would produce mojibake. Steps by Unicode scalar, exactly like Android's
    /// code-point walk, so both platforms cut the same text at the same place.
    public static func truncateToBytes(_ text: String, maxBytes: Int = maxTextBytes) -> String {
        if text.utf8.count <= maxBytes { return text }
        var bytes = 0
        var scalars = String.UnicodeScalarView()
        for scalar in text.unicodeScalars {
            let width = String(scalar).utf8.count
            if bytes + width > maxBytes { break }
            scalars.append(scalar)
            bytes += width
        }
        return String(scalars)
    }
}
