import Foundation

extension Duration {
    var inWholeMilliseconds: Int64 {
        let components = self.components
        let seconds = Int64(components.seconds) * 1_000
        let attoseconds = components.attoseconds / 1_000_000_000_000_000
        return seconds + Int64(attoseconds)
    }

    static func days(_ days: Int64) -> Duration {
        .seconds(days * 24 * 60 * 60)
    }

    static func hours(_ hours: Int64) -> Duration {
        .seconds(hours * 60 * 60)
    }
}
