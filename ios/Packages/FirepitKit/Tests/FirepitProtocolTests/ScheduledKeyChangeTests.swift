import FirepitProtocol
import Testing

@Suite("Scheduled key change")
struct ScheduledKeyChangeTests {
    @Test func makerConnectedDueWithEvidenceChanges() {
        #expect(
            ScheduledKeyChange.shouldChange(
                isMaker: true,
                setting: .daily,
                keyAgeMillis: ScheduledKeyChange.dayMillis,
                hasCurrentGenerationEvidence: true,
                connected: true,
                alreadyRotating: false))
    }

    @Test func blockersPreventChanging() {
        let due = ScheduledKeyChange.dayMillis
        #expect(!ScheduledKeyChange.shouldChange(isMaker: false, setting: .daily, keyAgeMillis: due, hasCurrentGenerationEvidence: true, connected: true, alreadyRotating: false))
        #expect(!ScheduledKeyChange.shouldChange(isMaker: true, setting: .never, keyAgeMillis: due, hasCurrentGenerationEvidence: true, connected: true, alreadyRotating: false))
        #expect(!ScheduledKeyChange.shouldChange(isMaker: true, setting: .daily, keyAgeMillis: due - 1, hasCurrentGenerationEvidence: true, connected: true, alreadyRotating: false))
        #expect(!ScheduledKeyChange.shouldChange(isMaker: true, setting: .daily, keyAgeMillis: due, hasCurrentGenerationEvidence: false, connected: true, alreadyRotating: false))
        #expect(!ScheduledKeyChange.shouldChange(isMaker: true, setting: .daily, keyAgeMillis: due, hasCurrentGenerationEvidence: true, connected: false, alreadyRotating: false))
        #expect(!ScheduledKeyChange.shouldChange(isMaker: true, setting: .daily, keyAgeMillis: due, hasCurrentGenerationEvidence: true, connected: true, alreadyRotating: true))
    }

    @Test func weeklyNeedsAWeek() {
        #expect(!ScheduledKeyChange.shouldChange(isMaker: true, setting: .weekly, keyAgeMillis: ScheduledKeyChange.weekMillis - 1, hasCurrentGenerationEvidence: true, connected: true, alreadyRotating: false))
        #expect(ScheduledKeyChange.shouldChange(isMaker: true, setting: .weekly, keyAgeMillis: ScheduledKeyChange.weekMillis, hasCurrentGenerationEvidence: true, connected: true, alreadyRotating: false))
    }
}
