import Foundation

/// Which traffic from the mesh is believed, stated as data rather than as
/// branches scattered through the repositories.
///
/// Everything that arrives over the air is attacker-controlled: node numbers,
/// room ids and invite ids can all be named by anyone who has seen them. Each
/// rule here was missing at some point, and each one is what stands between a
/// stranger with a radio and somebody else's room.
public enum TrustRules {
    /// Curve25519 public key length, which is what the firmware's PKI uses.
    public static let radioKeySize = 32

    /// A join hello is believed only when the firmware decrypted it with the
    /// very key it names.
    ///
    /// The key inside the hello is where the grant goes. Were it merely claimed,
    /// a spoofed hello could point the room's keys at a stranger; the firmware
    /// reports the key it actually decrypted with, and the two must agree.
    public static func helloIsBound(
        pkiEncrypted: Bool,
        addressedToUs: Bool,
        decryptedWith: Data,
        claimed: Data
    ) -> Bool {
        pkiEncrypted && addressedToUs && claimed.count == radioKeySize && decryptedWith == claimed
    }

    /// A later hello from the same node never replaces a waiting one that named
    /// different keys: the first one is what the person in front of you is
    /// checking the fingerprint of.
    public static func mayReplacePending(
        waitingRadioKey: Data?,
        waitingPhoneKey: Data?,
        radioKey: Data,
        phoneKey: Data
    ) -> Bool {
        waitingRadioKey == nil || (waitingRadioKey == radioKey && waitingPhoneKey == phoneKey)
    }

    /// What to do with the inviter a scanned code names, judged against the radio's own NodeDB.
    public enum Contact: Equatable, Sendable {
        /// The radio has never heard of them; the code's key is all there is.
        case add
        /// The radio already holds this very key. Adding it again is harmless, and repairs an eviction.
        case known
        /// The radio holds a different key for that node: the code is lying about who it is.
        case mismatch
    }

    public static func contactFor(knownKey: Data?, codeKey: Data) -> Contact {
        if knownKey == nil {
            return .add
        }
        if knownKey == codeKey {
            return .known
        }
        return .mismatch
    }

    /// A grant for a room this phone already holds is only taken from a member
    /// of it, and only to move it forward — otherwise a stranger's code naming
    /// one of our room ids would overwrite that room with keys they chose.
    public static func mayTakeGrant(
        alreadyHeld: Bool,
        senderIsMember: Bool,
        grantGeneration: Int32,
        currentGeneration: Int32,
        awaitingScannedInvite: Bool = false
    ) -> Bool {
        !alreadyHeld || (senderIsMember && grantGeneration > currentGeneration)
            || (awaitingScannedInvite && senderIsMember && grantGeneration == currentGeneration)
    }

    /// New keys for a room are believed only when they arrive privately, to us,
    /// from a member, sealed under the key they replace, and move the room on.
    ///
    /// Sealing under the current key is what makes the sender prove they hold
    /// it. Anyone can encrypt to a public key, so PKI alone proves nothing
    /// about membership.
    public static func rotationAcceptable(
        sealedUnderRoom: Int32?,
        sealedUnderGeneration: Int32?,
        rotationRoom: Int32,
        rotationGeneration: Int32,
        currentGeneration: Int32,
        privatelyToUs: Bool,
        senderIsMember: Bool
    ) -> Bool {
        sealedUnderRoom == rotationRoom && sealedUnderGeneration == currentGeneration && privatelyToUs && senderIsMember
            && rotationGeneration > currentGeneration
    }

    /// Where a sealed message may be believed.
    ///
    /// On the slot of the room it names, or directly to us over PKI. Otherwise a
    /// member of two rooms could have one room's words shown in the other.
    public static func sealedPlacementOk(slotRoom: Int32?, sealedRoom: Int32, privatelyToUs: Bool) -> Bool {
        privatelyToUs || slotRoom == sealedRoom
    }

    /// A roster sync is only the word of the inviter who let us in, sent to us
    /// alone, sealed under the room's current key, and only while they are
    /// still in the room: an inviter since removed would otherwise put
    /// themselves back on the list new keys go to. The seal also keeps the
    /// membership list away from the radios it passes through.
    public static func rosterSyncAcceptable(
        privatelyToUs: Bool,
        sender: Int32,
        ourInviter: Int32?,
        senderIsMember: Bool,
        sealedUnderCurrent: Bool
    ) -> Bool {
        privatelyToUs && sealedUnderCurrent && senderIsMember && ourInviter != nil && sender == ourInviter
    }

    /// A notice that the room has moved to a new key.
    ///
    /// Believed only sealed under the key we hold now, from a member, and naming
    /// the very next generation: it arrives before our own copy of the new key,
    /// or instead of it when that copy never reaches us, and either way we must
    /// stop talking under a key the removed member still holds. Exactly the next
    /// one, because a notice is always sealed under the key it replaces — and a
    /// removed member who still holds that key must not be able to name a
    /// generation so far ahead that no real key ever clears it.
    public static func rotationNoticeAcceptable(
        sealedUnderCurrent: Bool,
        senderIsMember: Bool,
        noticeGeneration: Int32,
        currentGeneration: Int32
    ) -> Bool {
        sealedUnderCurrent && senderIsMember && noticeGeneration == currentGeneration + 1
    }

    /// A member's position, sealed by their phone.
    ///
    /// Only under the room's current key, on the room's own slot: an older key
    /// is what a removed member still holds, and a room's positions belong in
    /// that room.
    public static func sealedPositionAcceptable(sealedUnderCurrent: Bool, onItsRoomSlot: Bool) -> Bool {
        sealedUnderCurrent && onItsRoomSlot
    }

    /// An unsealed position, from the firmware's own broadcast or the radio's
    /// node list.
    ///
    /// A node outside our rooms has nothing else to send, so its position is
    /// kept, as it always was, for the map's "everyone this radio has heard".
    /// A room member's phone only ever sends positions sealed, so an unsealed
    /// one naming a member is taken only as the opt-in safety net: on the
    /// channel of a Firepit room we share, never on the primary and never PKI,
    /// and it is marked as from their radio.
    public static func unsealedPositionAcceptable(
        senderInOurRooms: Bool,
        channel: Int,
        sharedFirepitRoomSlot: Int?,
        pkiEncrypted: Bool
    ) -> Bool {
        !senderInOurRooms
            || (!pkiEncrypted && channel != ChannelSlotManager.primarySlot && sharedFirepitRoomSlot == channel)
    }

    public static func radioPositionMayReplace(
        existingHasPosition: Bool,
        existingFromRadio: Bool,
        existingPositionTime: Int64?,
        incomingPositionTime: Int64?,
        nowMillis: Int64
    ) -> Bool {
        if !existingHasPosition { return true }
        if existingFromRadio {
            return existingPositionTime == nil
                || (incomingPositionTime != nil && incomingPositionTime! >= existingPositionTime!)
        }
        if let existingPositionTime, nowMillis - existingPositionTime < radioPositionSealedQuietMillis {
            return false
        }
        return existingPositionTime == nil || (incomingPositionTime != nil && incomingPositionTime! > existingPositionTime!)
    }

    private static let radioPositionSealedQuietMillis: Int64 = 10 * 60 * 1_000

    /// Whether to answer "where are you?".
    ///
    /// Only a question sealed under the room's current key, put to us alone,
    /// about a room we are sharing with right now. Anything else is somebody
    /// finding out where we are without our say.
    public static func positionQueryAnswerable(
        sealedUnderCurrent: Bool,
        addressedToUs: Bool,
        queryRoom: Int32,
        sharingWithRoom: Int32?
    ) -> Bool {
        sealedUnderCurrent && addressedToUs && sharingWithRoom != nil && sharingWithRoom == queryRoom
    }

    public enum PhoneKeySource: CaseIterable, Equatable, Sendable {
        /// This phone approved the join after an in-person fingerprint check.
        case inPerson
        /// A sealed JOINED roster event from the member who let the newcomer in.
        case vouched
        /// A person card, or any other announcement that carries a phone key.
        case announced
    }

    public struct PhoneKeyDecision: Equatable, Sendable {
        public var store: Bool
        public var inPerson: Bool
        public var replaced: Bool
    }

    /// Whether to store a phone key for a node, and whether it has in-person
    /// provenance.
    ///
    /// First sight is kept. The same key is kept as-is, except that seeing it in
    /// person marks it as in-person. A different in-person key replaces any old
    /// one; a vouched key replaces only one that was not learned in person; a
    /// plain announcement never replaces.
    public static func shouldStorePhoneKey(
        known: Data?,
        knownInPerson: Bool,
        incoming: Data,
        source: PhoneKeySource
    ) -> PhoneKeyDecision {
        if known == nil {
            return PhoneKeyDecision(store: true, inPerson: source == .inPerson, replaced: false)
        }
        if known == incoming && source == .inPerson && !knownInPerson {
            return PhoneKeyDecision(store: true, inPerson: true, replaced: false)
        }
        if known == incoming {
            return PhoneKeyDecision(store: false, inPerson: knownInPerson, replaced: false)
        }
        if source == .inPerson {
            return PhoneKeyDecision(store: true, inPerson: true, replaced: true)
        }
        if source == .vouched && !knownInPerson {
            return PhoneKeyDecision(store: true, inPerson: false, replaced: true)
        }
        return PhoneKeyDecision(store: false, inPerson: knownInPerson, replaced: false)
    }

    /// Whether a pin may be created or changed.
    ///
    /// Only sealed under a room's current key, so the sender is somebody who
    /// holds it rather than whoever holds a member's radio; never locked to
    /// somebody else, never moved to another room, and never overriding the lock
    /// its owner set. The seal binds `sender`, so a lock cannot be dodged by
    /// writing somebody else's number in a header.
    public static func pinUpdateAllowed(
        sealedInRoom: Int32?,
        sender: Int32,
        claimedLock: Int32,
        existingLock: Int32?,
        existingRoom: Int32?
    ) -> Bool {
        sealedInRoom != nil && (claimedLock == 0 || claimedLock == sender)
            && (existingRoom == nil || existingRoom == sealedInRoom)
            && (existingLock == nil || existingLock == 0 || existingLock == sender)
    }

    /// Whether `sender` may say they received or read one of our messages: the
    /// one person a direct message went to, or a member of the room it was sent
    /// in. Nobody else was ever meant to see it.
    public static func receiptAllowed(
        isOutgoing: Bool,
        sentTo: Int32,
        sender: Int32,
        senderIsRoomMember: Bool
    ) -> Bool {
        isOutgoing && (sentTo == MeshConstants.broadcastNodeNum ? senderIsRoomMember : sentTo == sender)
    }

    /// Whether unsealed text may be stored.
    ///
    /// A direct message only when the firmware decrypted it with the sender's
    /// key: the channel key is shared by everyone on the channel. Never on the
    /// primary, which is nobody's conversation, and never on a Firepit room's
    /// slot, where anything real arrives sealed and anything unsealed was typed
    /// by whoever holds a member's radio.
    public static func plainTextAcceptable(
        direct: Bool,
        pkiEncrypted: Bool,
        onPrimary: Bool,
        onFirepitRoom: Bool
    ) -> Bool {
        if direct {
            return pkiEncrypted
        }
        if onPrimary {
            return false
        }
        return !onFirepitRoom
    }
}
