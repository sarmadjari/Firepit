import Testing

@testable import FirepitProtocol

@Suite struct SavedRadiosTests {
    private func radio(_ id: String, _ name: String, _ role: NodeRole) -> SavedRadio {
        SavedRadio(identifier: id, name: name, role: role)
    }

    @Test func assigningPersonalTakesItFromWhoeverHeldIt() {
        let before = [radio("a", "Pocket", .personal)]
        let after = SavedRadios.assign(radios: before, radio: radio("b", "Spare", .personal))
        #expect(SavedRadios.personal(radios: after)?.identifier == "b")
        #expect(after.first { $0.identifier == "a" }?.role == .base)
    }

    @Test func aDemotedPersonalIsKeptNotForgotten() {
        let before = [radio("a", "Pocket", .personal)]
        let after = SavedRadios.assign(radios: before, radio: radio("b", "Spare", .personal))
        #expect(after.count == 2)
    }

    @Test func anyNumberOfBasesAndRouters() {
        var radios: [SavedRadio] = []
        radios = SavedRadios.assign(radios: radios, radio: radio("a", "Camp", .base))
        radios = SavedRadios.assign(radios: radios, radio: radio("b", "Hut", .base))
        radios = SavedRadios.assign(radios: radios, radio: radio("c", "Ridge", .router))
        radios = SavedRadios.assign(radios: radios, radio: radio("d", "Peak", .router))
        #expect(radios.count == 4)
        #expect(SavedRadios.personal(radios: radios) == nil)
    }

    @Test func reAssigningTheSameRadioReplacesItRatherThanDuplicating() {
        var radios = SavedRadios.assign(radios: [], radio: radio("a", "Camp", .base))
        radios = SavedRadios.assign(radios: radios, radio: radio("a", "Camp", .router))
        #expect(radios.count == 1)
        #expect(radios.single?.role == .router)
    }

    @Test func yoursSortsFirstTheRestByName() {
        var radios = SavedRadios.assign(radios: [], radio: radio("c", "Zulu", .base))
        radios = SavedRadios.assign(radios: radios, radio: radio("a", "alpha", .base))
        radios = SavedRadios.assign(radios: radios, radio: radio("b", "Pocket", .personal))
        #expect(radios.map { $0.name } == ["Pocket", "alpha", "Zulu"])
    }

    @Test func forgettingRemovesOnlyThatRadio() {
        var radios = SavedRadios.assign(radios: [], radio: radio("a", "Camp", .base))
        radios = SavedRadios.assign(radios: radios, radio: radio("b", "Hut", .base))
        #expect(SavedRadios.forget(radios: radios, identifier: "a").map { $0.identifier } == ["b"])
    }

    @Test func demotingTheOnlyPersonalLeavesNobodyHoldingIt() {
        let before = [radio("a", "Pocket", .personal)]
        let after = SavedRadios.assign(radios: before, radio: radio("a", "Pocket", .base))
        #expect(SavedRadios.personal(radios: after) == nil)
    }

    @Test func radiosKeptOffTheMapAreReportedByNodeNumber() {
        let radios = [
            SavedRadio(identifier: "a", name: "Pocket", role: .personal, nodeNum: 1),
            SavedRadio(identifier: "b", name: "Camp", role: .base, nodeNum: 2, onMap: false),
            SavedRadio(identifier: "c", name: "Hilltop", role: .router, nodeNum: 3, onMap: false),
        ]
        #expect(SavedRadios.hiddenNodes(radios: radios) == Set<Int32>([2, 3]))
    }

    @Test func aRadioNeverConnectedCannotBeHiddenHavingNoNodeToHide() {
        let radios = [SavedRadio(identifier: "b", name: "Camp", role: .base, onMap: false)]
        #expect(SavedRadios.hiddenNodes(radios: radios) == Set<Int32>())
    }
}

extension Array {
    fileprivate var single: Element? {
        count == 1 ? self[0] : nil
    }
}
