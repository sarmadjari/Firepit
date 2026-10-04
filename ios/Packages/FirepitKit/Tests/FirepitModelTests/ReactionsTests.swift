import Testing

@testable import FirepitModel

/// Ported from android/core/model/src/test/…/ReactionsTest.kt, case for case.
@Suite("Reactions")
struct ReactionsTests {
    private let me: Int32 = 1
    private let maya: Int32 = 2
    private let ryan: Int32 = 3

    private func message(
        _ id: Int32, from: Int32, text: String = "Hi", replyId: Int32? = nil, emoji: Int? = nil, at: Int64? = nil
    ) -> ChatMessage {
        ChatMessage(
            id: id, channel: 1, fromNodeNum: from, toNodeNum: broadcastNodeNum, text: text, sentAt: at ?? Int64(id),
            isOutgoing: from == me, replyId: replyId, emoji: emoji)
    }

    @Test func thereAreSixChoicesInThePickersOrder() {
        #expect(Reactions.choices == ["👍", "❤️", "😂", "😮", "😢", "🙏"])
    }

    @Test func onlyAnEmojiAimedAtAMessageIsAReaction() {
        #expect(Reactions.isReaction(message(2, from: maya, text: "👍", replyId: 1, emoji: 1)))
        #expect(!Reactions.isReaction(message(2, from: maya, text: "👍", replyId: 1)))
        #expect(!Reactions.isReaction(message(2, from: maya, text: "👍", emoji: 1)))
    }

    @Test func reactionsAreCountedUnderTheirMessageTheSixInOrderAndYoursMarked() {
        let counts = Reactions.countsByTarget(
            [
                message(1, from: maya, text: "Coffee?"),
                message(2, from: ryan, text: "❤️", replyId: 1, emoji: 1),
                message(3, from: me, text: "👍", replyId: 1, emoji: 1),
                message(4, from: maya, text: "👍", replyId: 1, emoji: 1),
            ],
            myNodeNum: me
        )
        #expect(
            counts[1] == [
                Reactions.Count(emoji: "👍", count: 2, mine: true), Reactions.Count(emoji: "❤️", count: 1, mine: false),
            ])
    }

    @Test func aPersonsLatestReactionReplacesTheirEarlierOne() {
        let counts = Reactions.countsByTarget(
            [
                message(2, from: maya, text: "👍", replyId: 1, emoji: 1, at: 10),
                message(3, from: maya, text: "😂", replyId: 1, emoji: 1, at: 20),
            ],
            myNodeNum: me
        )
        #expect(counts[1] == [Reactions.Count(emoji: "😂", count: 1, mine: false)])
    }

    @Test func anEmojiAnotherAppSentFollowsTheSix() {
        let counts = Reactions.countsByTarget(
            [
                message(2, from: maya, text: "🔥", replyId: 1, emoji: 1),
                message(3, from: ryan, text: "🙏", replyId: 1, emoji: 1),
            ],
            myNodeNum: me
        )
        #expect(counts[1]?.map(\.emoji) == ["🙏", "🔥"])
    }
}
