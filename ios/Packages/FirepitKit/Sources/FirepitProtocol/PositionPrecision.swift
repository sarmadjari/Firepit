import Foundation

/// Coordinate truncation, matching the firmware bit for bit.
///
/// A room can be shared at reduced precision so members see roughly where somebody is without publishing their
/// doorstep. The firmware masks the low bits and then adds half a cell, so the result sits in the middle of the
/// possible area rather than at its corner. Anything else here would disagree with what other clients draw for the same
/// packet.
public enum PositionPrecision {
    /// Positions are never transmitted on a channel set to this.
    public static let disabled = 0

    /// No truncation: the coordinate goes out exactly as the GPS reported it.
    public static let full = 32

    /// Truncates a 1e-7 degree coordinate to [precisionBits].
    ///
    /// [full] and [disabled] are returned untouched because the shift below is only defined for 1..31: Kotlin masks Int
    /// shift counts to five bits, so `1 shl -1` would silently become `1 shl 31` and move the point across the planet.
    public static func truncate(coordinate: Int32, precisionBits: Int) -> Int32 {
        if precisionBits >= full || precisionBits <= disabled { return coordinate }

        let mask = Int32(bitPattern: UInt32.max << UInt32(full - precisionBits))
        let masked = coordinate & mask
        let halfCell = Int32(1 << (full - 1 - precisionBits))
        return masked &+ halfCell
    }

    /// How many 1e-7 degree units wide one cell is at [precisionBits].
    public static func cellSize(precisionBits: Int) -> Int64 {
        if precisionBits >= full || precisionBits <= disabled { return 1 }
        return Int64(1) << Int64(full - precisionBits)
    }
}
