import Testing

@testable import FirepitProtocol

@Suite struct OwnerNameTests {
    @Test func initialsWhenTheNameHasSeveralWords() {
        #expect(OwnerName.suggestShort(longName: "Sam Jones") == "SJ")
        #expect(OwnerName.suggestShort(longName: "anna bea carter") == "ABC")
    }

    @Test func openingLettersWhenTheNameIsOneWord() {
        #expect(OwnerName.suggestShort(longName: "Sarmad") == "SARM")
    }

    @Test func aLongSingleWordIsCutToTheRadiosFourBytes() {
        #expect(OwnerName.suggestShort(longName: "Bartholomew").count == 4)
    }

    @Test func arabicIsCutOnACharacterNotInTheMiddleOfOne() {
        let short = OwnerName.shortName(text: "سلام")
        #expect(short == "سل")
        #expect(short.utf8.count <= OwnerName.maxShortBytes)
    }

    @Test func anEmojiIsKeptWholeOrDroppedNeverSplit() {
        let short = OwnerName.shortName(text: "🔥🔥")
        #expect(short == "🔥")
    }

    @Test func aLongNameIsCappedAtThirtyNineBytes() {
        let long = OwnerName.longName(text: String(repeating: "x", count: 80))
        #expect(long.count == OwnerName.maxLongBytes)
    }

    @Test func surroundingSpaceIsNotSpentOnTheBudget() {
        #expect(OwnerName.longName(text: "  Sam  ") == "Sam")
    }

    @Test func fitsReportsWhatTheRadioWillAccept() {
        #expect(OwnerName.fits(longName: "Sam Jones", shortName: "SJ"))
        #expect(!OwnerName.fits(longName: String(repeating: "x", count: 40), shortName: "SJ"))
        #expect(!OwnerName.fits(longName: "Sam", shortName: "TOOLONG"))
    }
}
