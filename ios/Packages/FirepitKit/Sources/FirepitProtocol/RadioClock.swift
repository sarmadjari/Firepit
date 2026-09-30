/// Reading timestamps that came off a radio.
///
/// A Meshtastic node keeps its own clock, and one that has never been told the
/// time sits at zero or drifts years out. Nothing should be shown to a reader on
/// a radio's word alone.
public enum RadioClock {
    /// Older than the app keeps anything, so such a stamp is the clock talking.
    public static let claimStaleMs: Int64 = 30 * 24 * 60 * 60 * 1_000

    /// Room for a little drift forward; nobody transmits from the future.
    public static let claimAheadMs: Int64 = 5 * 60 * 1_000

    /// A stamp from the radio in our own hand, read in the phone's terms.
    public static func onPhoneClock(radioSeconds: Int32, skewMillis: Int64?, now: Int64) -> Int64? {
        if radioSeconds == 0 {
            return nil
        }
        let corrected = Int64(radioSeconds) * 1_000 - (skewMillis ?? 0)
        return min(corrected, now)
    }

    /// A stamp another node put on its own fix, believed only while it could be
    /// true.
    public static func ifPlausible(claimSeconds: Int32, now: Int64) -> Int64? {
        if claimSeconds == 0 {
            return nil
        }
        let claim = Int64(claimSeconds) * 1_000
        if claim >= now - claimStaleMs && claim <= now + claimAheadMs {
            return claim
        }
        return nil
    }
}
