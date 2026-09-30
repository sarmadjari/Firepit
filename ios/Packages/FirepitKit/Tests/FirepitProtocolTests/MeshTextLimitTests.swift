import Testing

@testable import FirepitProtocol

@Suite struct MeshTextLimitTests {
    @Test func textWithinTheLimitIsReturnedUntouched() {
        let text = "Meet at the north gate"
        #expect(MeshConstants.truncateToBytes(text) == text)
    }

    @Test func asciiIsCutAtTheByteLimit() {
        let text = String(repeating: "x", count: 300)
        let result = MeshConstants.truncateToBytes(text)
        #expect(result.count == MeshConstants.maxTextBytes)
    }

    @Test func arabicIsNeverSplitMidCharacter() {
        let text = String(repeating: "مرحبا", count: 60)
        let result = MeshConstants.truncateToBytes(text, maxBytes: 15)
        #expect(result.utf8.count <= 15)
        #expect(!result.contains("\u{FFFD}"))
        #expect(text.hasPrefix(result))
    }

    @Test func emojiAreNeverSplitIntoLoneSurrogates() {
        let text = String(repeating: "🔥", count: 10)
        let result = MeshConstants.truncateToBytes(text, maxBytes: 10)
        #expect(result == "🔥🔥")
        let units = Array(result.utf16)
        for index in units.indices {
            let unit = units[index]
            if (0xD800...0xDBFF).contains(unit) {
                #expect(index + 1 < units.count && (0xDC00...0xDFFF).contains(units[index + 1]))
            }
            if (0xDC00...0xDFFF).contains(unit) {
                #expect(index > 0 && (0xD800...0xDBFF).contains(units[index - 1]))
            }
        }
    }

    @Test func aLimitSmallerThanTheFirstCharacterYieldsEmptyRatherThanGarbage() {
        let result = MeshConstants.truncateToBytes("🔥", maxBytes: 3)
        #expect(result == "")
    }
}
