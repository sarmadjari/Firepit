import Testing

@testable import FirepitProtocol

/// Ported from android/core/protocol/src/test/…/QuickRepliesTest.kt, case for case.
struct QuickRepliesTests {
    @Test func theDefaultsAreFiveAndEachOneFits() {
        #expect(QuickReplies.defaults.count == 5)
        for reply in QuickReplies.defaults {
            #expect(reply.utf8.count <= QuickReplies.maxBytes)
            #expect(QuickReplies.clean(reply) == reply)
        }
    }

    @Test func aReplyIsOneTrimmedLine() {
        #expect(QuickReplies.clean("  Back at\nthe   car \t") == "Back at the car")
    }

    @Test func nothingButSpacesIsNoReply() {
        #expect(QuickReplies.clean(" \n\t ") == nil)
    }

    @Test func aLongReplyIsCutAtTheByteLimitWithoutSplittingACharacter() {
        let cleaned = QuickReplies.clean(String(repeating: "ب", count: 30))
        #expect(cleaned?.count == 20)
        #expect((cleaned?.utf8.count ?? 0) <= QuickReplies.maxBytes)
    }

    @Test func anEmojiIsNeverSplit() {
        #expect(QuickReplies.clean(String(repeating: "x", count: 38) + "🔥") == String(repeating: "x", count: 38))
    }

    @Test func theKeptListDropsEmptyOnesAndRepeatsAndStopsAtTheMostThereCanBe() {
        let many = (1...15).map { "Reply \($0)" }
        #expect(QuickReplies.normalise(many).count == QuickReplies.maxCount)
        #expect(QuickReplies.normalise(["OK", " ", "OK ", "Here"]) == ["OK", "Here"])
    }
}
