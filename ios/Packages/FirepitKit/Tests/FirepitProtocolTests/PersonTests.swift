import Testing

@testable import FirepitProtocol

@Suite struct PersonTests {
    @Test func keepsTheNameAndTagItIsGiven() {
        let person = Person.of(id: 7, name: "Sarmad", tag: "SRM")
        #expect(person.name == "Sarmad")
        #expect(person.tag == "SRM")
    }

    @Test func suggestsInitialsWhenNoTagIsOffered() {
        #expect(Person.of(id: 1, name: "Sarmad Jamal", tag: "").tag == "SJ")
    }

    @Test func oneNameGivesItsOpeningTwoLetters() {
        #expect(Person.initialsFor(name: "Sarmad") == "SA")
    }

    @Test func aSurnameReplacesTheSecondLetter() {
        #expect(Person.initialsFor(name: "Sarmad Jamal") == "SJ")
    }

    @Test func middleNamesAreSkippedInFavourOfTheSurname() {
        #expect(Person.initialsFor(name: "Anna Bea Carter") == "AC")
    }

    @Test func aSingleLetterStandsAloneRatherThanBeingPadded() {
        #expect(Person.initialsFor(name: "S") == "S")
    }

    @Test func noNameGivesNoInitials() {
        #expect(Person.initialsFor(name: "   ") == "")
    }

    @Test func initialsFollowAlphabetsThatHaveNoCapitals() {
        #expect(Person.initialsFor(name: "سرمد جمال") == "سج")
    }

    @Test func aNameTooLongForAControlPacketIsCutToFit() {
        let person = Person.of(id: 1, name: String(repeating: "x", count: 80), tag: "X")
        #expect(person.name.utf8.count == OwnerName.maxLongBytes)
    }

    @Test func aTagLongerThanFourCharactersIsCutToFit() {
        #expect(Person.of(id: 1, name: "Sarmad", tag: "SARMAD").tag == "SARM")
    }

    @Test func countsBytesRatherThanCharactersSoOtherAlphabetsSurvive() {
        let person = Person.of(id: 1, name: "سرمد", tag: "سر")
        #expect(person.name == "سرمد")
        #expect(person.tag == "سر")
    }

    @Test func theIdentityIsNotDerivedFromAnyNodeSoItSurvivesChangingRadio() {
        let onPocketRadio = Person.of(id: 4242, name: "Sarmad", tag: "SRM", colourSlot: 3)
        let onBaseStation = Person.of(id: 4242, name: "Sarmad", tag: "SRM", colourSlot: 3)
        #expect(onPocketRadio == onBaseStation)
    }

    @Test func noColourChosenStaysNoColourChosen() {
        #expect(Person.of(id: 1, name: "Sarmad", tag: "SRM").colourSlot == nil)
    }
}
