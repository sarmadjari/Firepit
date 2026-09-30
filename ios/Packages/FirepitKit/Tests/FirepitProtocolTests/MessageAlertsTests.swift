import FirepitProtos
import Testing

@testable import FirepitProtocol

@Suite struct MessageAlertsTests {
    @Test func aRadioWithNoAlertSectionAnnouncesNothing() {
        #expect(MessageAlerts.of(config: nil) == .phoneOnly)
    }

    @Test func theModuleBeingOnIsNotEnoughOnItsOwn() {
        var enabledButSilent = ModuleConfig.ExternalNotificationConfig()
        enabledButSilent.enabled = true
        enabledButSilent.alertMessage = false
        #expect(MessageAlerts.of(config: enabledButSilent) == .phoneOnly)
    }

    @Test func alertingOnAMessageReadsAsTheRadioJoiningIn() {
        var alerting = ModuleConfig.ExternalNotificationConfig()
        alerting.enabled = true
        alerting.alertMessage = true
        #expect(MessageAlerts.of(config: alerting) == .phoneAndNode)
    }

    @Test func writingTheChoiceKeepsTheWiringTheRadioReported() {
        var wired = ModuleConfig.ExternalNotificationConfig()
        wired.output = 13
        wired.outputBuzzer = 25
        wired.outputVibra = 14
        wired.outputMs = 900
        wired.nagTimeout = 60
        wired.usePwm = true
        wired.active = true
        let written = MessageAlerts.applyTo(config: wired, choice: .phoneAndNode)
        #expect(written.output == 13)
        #expect(written.outputBuzzer == 25)
        #expect(written.outputVibra == 14)
        #expect(written.outputMs == 900)
        #expect(written.nagTimeout == 60)
        #expect(written.usePwm)
        #expect(written.active)
    }

    @Test func choosingTheRadioTurnsOnEveryWayABoardMightHaveOfSayingSo() {
        let written = MessageAlerts.applyTo(config: nil, choice: .phoneAndNode)
        #expect(written.enabled)
        #expect(written.alertMessage)
        #expect(written.alertMessageBuzzer)
        #expect(written.alertMessageVibra)
    }

    @Test func aBellIsAnnouncedAlongsideAMessage() {
        let written = MessageAlerts.applyTo(config: nil, choice: .phoneAndNode)
        #expect(written.alertBell)
        #expect(written.alertBellBuzzer)
        #expect(written.alertBellVibra)
    }

    @Test func choosingThePhoneAloneSilencesTheRadioWithoutRewiringIt() {
        var wired = ModuleConfig.ExternalNotificationConfig()
        wired.enabled = true
        wired.alertMessage = true
        wired.alertBell = true
        wired.outputBuzzer = 25
        let written = MessageAlerts.applyTo(config: wired, choice: .phoneOnly)
        #expect(!written.enabled)
        #expect(!written.alertMessage)
        #expect(!written.alertBell)
        #expect(written.outputBuzzer == 25)
    }

    @Test func whatWasWrittenReadsBackAsWhatWasChosen() {
        for choice in MessageAlerts.allCases {
            #expect(MessageAlerts.of(config: MessageAlerts.applyTo(config: nil, choice: choice)) == choice)
        }
    }
}
