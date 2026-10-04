import Foundation

/// The one local "you" fix Firepit may show or seal. Pure port of Android `OwnPosition`.
///
/// The phone wins: faster, more accurate, and it spares the radio's smaller battery. The radio's own GPS stands in
/// when the phone has nothing, or has gone quiet while the radio kept fixing. A phone standing still reports less
/// often (iOS pauses its updates), so its fix stays good for a while.
public enum OwnPosition {
    public static let phoneFreshMillis: Int64 = 10 * 60 * 1_000
    public static let radioFreshMillis: Int64 = 10 * 60 * 1_000
    /// How much newer the radio's fix must be before it is shown instead of the phone's.
    public static let phoneQuietMillis: Int64 = 2 * 60 * 1_000
    /// A fix stamped this far ahead of our clock still counts: phone, radio and GPS clocks differ.
    public static let clockSkewMillis: Int64 = 5 * 60 * 1_000

    public enum Source: Sendable, Equatable {
        case phone
        case radio
    }

    public struct Fix: Sendable, Equatable {
        public var latitude: Double
        public var longitude: Double
        public var altitude: Int?
        public var timeMillis: Int64
        public var source: Source

        public init(latitude: Double, longitude: Double, altitude: Int?, timeMillis: Int64, source: Source) {
            self.latitude = latitude
            self.longitude = longitude
            self.altitude = altitude
            self.timeMillis = timeMillis
            self.source = source
        }

        public var latitudeI: Int32 { Int32(latitude * 1e7) }
        public var longitudeI: Int32 { Int32(longitude * 1e7) }
    }

    public static func choose(phone: Fix?, radio: Fix?, nowMillis: Int64, locationAllowed: Bool) -> Fix? {
        guard locationAllowed else { return nil }
        let fromPhone = phone.flatMap { fresh($0, nowMillis: nowMillis, limit: phoneFreshMillis) ? $0 : nil }
        let fromRadio = radio.flatMap { fresh($0, nowMillis: nowMillis, limit: radioFreshMillis) ? $0 : nil }
        guard let fromPhone else { return fromRadio }
        guard let fromRadio else { return fromPhone }
        return fromRadio.timeMillis - fromPhone.timeMillis > phoneQuietMillis ? fromRadio : fromPhone
    }

    private static func fresh(_ fix: Fix, nowMillis: Int64, limit: Int64) -> Bool {
        (-clockSkewMillis...limit).contains(nowMillis - fix.timeMillis)
    }

    public static func distanceMetres(_ a: Fix, _ b: Fix) -> Double {
        let earth = 6_371_000.0
        let lat1 = a.latitude * .pi / 180
        let lat2 = b.latitude * .pi / 180
        let dLat = lat2 - lat1
        let dLon = (b.longitude - a.longitude) * .pi / 180
        let h = pow(sin(dLat / 2), 2) + cos(lat1) * cos(lat2) * pow(sin(dLon / 2), 2)
        return 2 * earth * asin(sqrt(h))
    }
}
