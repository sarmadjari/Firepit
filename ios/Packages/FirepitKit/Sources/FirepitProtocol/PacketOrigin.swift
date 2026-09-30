import FirepitProtos

/// How far a packet travelled, for the one decision that depends on it: a QR
/// code is shown to somebody standing in front of you, so a join hello that
/// needed relaying was read somewhere you cannot see.
///
/// Meshtastic exposes this as two fields rather than a hop count. The
/// originator sets `hopStart` to its initial `hopLimit`, and every relay
/// decrements `hopLimit`, so the difference is the distance travelled. Neither
/// field is authenticated, which is the whole reason this is written down: the
/// arithmetic has to be read as a claim, and an impossible claim is a lie.
public enum PacketOrigin {
    /// True only when the packet claims a distance of zero and the claim is
    /// internally consistent.
    ///
    /// Fails closed. A sender that wants to look adjacent from three hops away
    /// sets `hopStart = 0` and `hopLimit = 3`; relays decrement the limit and
    /// it arrives claiming a negative distance, which no honest packet can. A
    /// direct packet is `hopStart == hopLimit`, whether both are zero
    /// (send-to-neighbour) or both are three (untouched by any relay).
    public static func arrivedDirectly(packet: MeshPacket) -> Bool {
        hopsTravelled(packet: packet) == 0
    }

    /// Hops between origin and here, negative when the two fields disagree in a
    /// way no relay could produce.
    public static func hopsTravelled(packet: MeshPacket) -> Int {
        Int(packet.hopStart) - Int(packet.hopLimit)
    }
}
