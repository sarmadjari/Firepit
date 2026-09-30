import FirepitProtocol
import FirepitProtos
import Foundation

/// Encodes invites for QR codes.
///
/// The payload only ever travels on a screen or in a link, never over LoRa, so its size is bounded by what scans
/// reliably rather than by the 233-byte mesh budget.
public enum InviteCodec {
    public static let scheme = "firepit"
    public static let version: UInt32 = 1

    /// nanopb caps `ChannelSettings.name` at 12 bytes including the terminator.
    public static let maxRoomNameBytes = 11
    private static let publicKeySize = 32

    private static let prefix = "\(scheme)://join?v=\(version)&d="

    public static func encode(_ invite: Meshchat_Invite) -> String {
        let bytes: Data = (try? invite.serializedBytes()) ?? Data()
        return prefix + Base64URL.encode(bytes)
    }

    /// True when a scan carries Firepit's own marker, whatever the payload turns out to be.
    ///
    /// The scheme is the identifier, and it is the first nine characters, so this settles which decoder to use without
    /// base64 or protobuf work. A code that answers true here is Firepit's to explain: falling through to the
    /// Meshtastic decoder would report "not a Meshtastic link" about something that was plainly one of ours.
    public static func isFirepitCode(_ uri: String) -> Bool {
        uri.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().hasPrefix("\(scheme)://join")
    }

    /// The version a code declares, read from the link itself rather than from its payload, so a format this build
    /// cannot parse can still be recognised and named.
    public static func declaredVersion(_ uri: String) -> Int? {
        let trimmed = uri.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let range = trimmed.range(of: "?v=") else { return nil }
        let rest = trimmed[range.upperBound...]
        let value = rest.firstIndex(of: "&").map { rest[..<$0] } ?? rest
        return Int(value)
    }

    /// Nil for anything that is not a well-formed invite of a version we understand.
    public static func decode(_ uri: String) -> Meshchat_Invite? {
        let trimmed = uri.trimmingCharacters(in: .whitespacesAndNewlines)
        guard isFirepitCode(trimmed) else { return nil }
        var payload = substring(of: trimmed, after: "&d=")
        if payload.isEmpty { payload = substring(of: trimmed, after: "?d=") }
        guard !payload.isEmpty,
            let bytes = Base64URL.decode(payload),
            let invite = try? Meshchat_Invite(serializedBytes: bytes),
            isUsable(invite)
        else { return nil }
        return invite
    }

    /// Whether this invite stops working on its own.
    ///
    /// Without a token an invite is an open door: it carries no keys, but it would let anyone ask to be let in at any
    /// time.
    public static func isTimeBound(_ invite: Meshchat_Invite) -> Bool { invite.token.count == RoomCrypto.tokenSize }

    /// Rejects invites that would produce a broken room, or one we could never be let into.
    ///
    /// The inviter's public key is the load-bearing field now: without it there is nowhere to send a hello that only
    /// they can open, and no way for the keys to come back.
    private static func isUsable(_ invite: Meshchat_Invite) -> Bool {
        invite.version == version
            && invite.roomID != 0
            && invite.roomName.utf8.count <= maxRoomNameBytes
            && invite.inviter.nodeNum != 0
            && invite.inviter.user.publicKey.count == publicKeySize
    }

    /// Kotlin's `substringAfter(delimiter, "")`: everything after the first occurrence, or empty.
    private static func substring(of text: String, after delimiter: String) -> String {
        guard let range = text.range(of: delimiter) else { return "" }
        return String(text[range.upperBound...])
    }
}

/// What a scanned code turned out to be.
///
/// One camera reads two unrelated formats, and which one a code is decides how private the resulting room can be.
/// Keeping the answer as a type means the caller has to deal with the cases where it is ours but unusable, instead of
/// letting those slide into "unrecognised".
public enum ScannedCode: Equatable, Sendable {
    /// A Firepit invite: a sealed room, with everything Firepit adds.
    case firepit(Meshchat_Invite)
    /// A Meshtastic channel link: plain Meshtastic, readable by other clients.
    case meshtastic(ChannelUrl.Shared)
    /// Firepit's marker, but this build cannot use what follows it.
    case firepitUnreadable(Reason)
    case unrecognised

    public enum Reason: Sendable {
        /// Issued by a later version of the app than this one.
        case newerVersion
        /// Damaged, truncated, or already expired.
        case malformed
    }
}

/// Decides which decoder a scan belongs to before doing any decoding.
///
/// Firepit's scheme is checked first because it is a nine-character prefix and settles the question outright. Only a
/// code without it is offered to the Meshtastic decoder, which has to base64 and parse a protobuf to find out.
public enum CodeScanner {
    public static func classify(_ scanned: String) -> ScannedCode {
        guard InviteCodec.isFirepitCode(scanned) else {
            return ChannelUrl.decode(scanned).map(ScannedCode.meshtastic) ?? .unrecognised
        }
        if let declared = InviteCodec.declaredVersion(scanned), declared > Int(InviteCodec.version) {
            return .firepitUnreadable(.newerVersion)
        }
        // Ours either way from here: a broken Firepit code is never reported as an unrecognised one, because the person
        // scanning it knows what it was.
        return InviteCodec.decode(scanned).map(ScannedCode.firepit) ?? .firepitUnreadable(.malformed)
    }
}
