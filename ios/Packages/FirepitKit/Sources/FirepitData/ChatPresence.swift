import FirepitModel
import Foundation

/// Which conversation is on screen, and whether the app is being looked at.
///
/// Notifying someone about a message they are already reading is noise, so the UI reports what it is showing and the
/// notifier respects it.
public final class ChatPresence: Sendable {
    public let openChannel = CurrentValue<Int?>(nil)
    public let foreground = CurrentValue(false)

    public init() {}

    public func setOpenChannel(_ channel: Int?) {
        openChannel.set(channel)
    }

    public func setForeground(_ foreground: Bool) {
        self.foreground.set(foreground)
    }

    /// True when a new message on channel would land in front of the reader.
    public func isWatching(channel: Int) -> Bool {
        foreground.value && openChannel.value == channel
    }
}
