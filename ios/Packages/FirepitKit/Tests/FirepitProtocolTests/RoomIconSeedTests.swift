import FirepitModel
import Testing

@testable import FirepitProtocol

@Suite struct RoomIconSeedTests {
    private func room(name: String, id: Int32, kind: RoomKind, index: Int = 1) -> RoomChannel {
        RoomChannel(index: index, name: name, role: .secondary, id: id, positionPrecision: 0, kind: kind)
    }

    @Test func aMeshtasticChannelLooksTheSameHoweverEachPhoneLearnedOfIt() {
        let madeInMeshtasticApp = room(name: "SJ", id: 0x5A5A5A5A, kind: .meshtasticPrivate)
        let addedByFirepit = room(name: "SJ", id: 0, kind: .meshtasticPrivate)
        let typedInByHand = room(name: "SJ", id: 0x0BADF00D, kind: .meshtasticPrivate)
        #expect(madeInMeshtasticApp.iconSeed == addedByFirepit.iconSeed)
        #expect(madeInMeshtasticApp.iconSeed == typedInByHand.iconSeed)
    }

    @Test func aPublicChannelBehavesTheSameWay() {
        #expect(
            room(name: "LongFast", id: 0, kind: .meshtasticPublic).iconSeed
                == room(name: "LongFast", id: 99, kind: .meshtasticPublic).iconSeed)
    }

    @Test func aFirepitRoomFollowsItsSharedId() {
        let mine = room(name: "camp", id: 0x0BADF00D, kind: .firepit)
        let theirs = room(name: "camp", id: 0x0BADF00D, kind: .firepit, index: 4)
        #expect(mine.iconSeed == theirs.iconSeed)
        #expect(mine.iconSeed == 0x0BADF00D)
    }

    @Test func theSlotARoomSitsInDoesNotChangeItsIcon() {
        for slot in 1...7 {
            #expect(
                room(name: "SJ", id: 0, kind: .meshtasticPrivate, index: 1).iconSeed
                    == room(name: "SJ", id: 0, kind: .meshtasticPrivate, index: slot).iconSeed)
        }
    }

    @Test func differentChannelsMostlyGetDifferentIcons() {
        let names = ["SJ", "camp", "LongFast", "trail", "base", "north", "crew", "hut"]
        let icons = names.map { name in
            seedToIcon(seed: room(name: name, id: 0, kind: .meshtasticPrivate).iconSeed)
        }
        #expect(Set(icons).count >= 4)
    }

    @Test func aRoomWithNoIdFallsBackToItsName() {
        let nameless = room(name: "camp", id: 0, kind: .firepit)
        #expect(nameless.iconSeed == room(name: "camp", id: 0, kind: .meshtasticPrivate).iconSeed)
    }

    @Test func theNameHashIsFixedSoOtherPlatformsCanMatchIt() {
        #expect(room(name: "", id: 0, kind: .meshtasticPrivate).iconSeed == -0x7ee3623b)
        #expect(room(name: "SJ", id: 0, kind: .meshtasticPrivate).iconSeed == 1_560_448_184)
    }

    private func seedToIcon(seed: Int32) -> Int32 {
        ((seed % 8) + 8) % 8
    }
}
