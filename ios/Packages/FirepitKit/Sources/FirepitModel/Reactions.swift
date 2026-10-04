import Foundation

/// The six fixed reactions (UX §5.4, decision U-1) and how they are counted. Ported from
/// android/core/model/…/Reactions.kt.
///
/// A reaction travels as a message of its own: one emoji, Meshtastic's `emoji` flag, and the id of the message it
/// reacts to. It is shown under that message, never as a line of the conversation.
public enum Reactions {
    /// In the order the picker shows them.
    public static let choices = ["👍", "❤️", "😂", "😮", "😢", "🙏"]

    /// One emoji's count under a message, and whether one of them is yours.
    public struct Count: Equatable, Hashable, Sendable {
        public let emoji: String
        public let count: Int
        public let mine: Bool

        public init(emoji: String, count: Int, mine: Bool) {
            self.emoji = emoji
            self.count = count
            self.mine = mine
        }
    }

    /// A message that reacts to another rather than saying something.
    public static func isReaction(_ message: ChatMessage) -> Bool {
        (message.emoji ?? 0) != 0 && message.replyId != nil
    }

    /// Each message's reactions, by the id of the message they react to.
    ///
    /// A mesh cannot take a reaction back, so a person's latest one stands for theirs: choosing again replaces it
    /// rather than adding a second. The six come first in the picker's order, then anything another app sent.
    public static func countsByTarget(_ messages: [ChatMessage], myNodeNum: Int32?) -> [Int32: [Count]] {
        let reactions = messages.filter(isReaction)
        let byTarget = Dictionary(grouping: reactions) { $0.replyId! }
        return byTarget.mapValues { targetReactions in
            let latest = Dictionary(grouping: targetReactions, by: \.fromNodeNum).values.compactMap { theirs in
                theirs.max { $0.sentAt < $1.sentAt }
            }
            return Dictionary(grouping: latest, by: \.text)
                .map { emoji, from in
                    Count(
                        emoji: emoji,
                        count: from.count,
                        mine: from.contains { $0.isOutgoing || $0.fromNodeNum == myNodeNum }
                    )
                }
                .sorted { first, second in
                    let a = order(first.emoji)
                    let b = order(second.emoji)
                    return a != b ? a < b : first.emoji < second.emoji
                }
        }
    }

    private static func order(_ emoji: String) -> Int { choices.firstIndex(of: emoji) ?? choices.count }
}
