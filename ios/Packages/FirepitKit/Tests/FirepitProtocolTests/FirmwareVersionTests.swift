import Testing

@testable import FirepitProtocol

@Suite struct FirmwareVersionTests {
    @Test func parsesAReleaseVersionWithABuildSuffix() {
        let version = FirmwareVersion.parseOrNull("2.7.26.54e0d8d")

        #expect(FirmwareVersion(major: 2, minor: 7, patch: 26, raw: "2.7.26.54e0d8d") == version)
    }

    @Test func parsesADevelopmentVersion() throws {
        let version = try #require(FirmwareVersion.parseOrNull("2.8.1-dev"))

        #expect(2 == version.major)
        #expect(8 == version.minor)
        #expect(1 == version.patch)
    }

    @Test func parsesATwoPartVersion() {
        #expect(0 == FirmwareVersion.parseOrNull("2.8")?.patch)
    }

    @Test func rejectsJunkRatherThanGuessing() {
        #expect(FirmwareVersion.parseOrNull("") == nil)
        #expect(FirmwareVersion.parseOrNull("unknown") == nil)
    }

    private func capabilities(firmware: String?) -> RadioCapabilities {
        RadioCapabilities(
            firmwareVersion: firmware.flatMap(FirmwareVersion.parseOrNull),
            supportsPki: true,
            supportsSigning: false,
            minAppVersion: 0,
            nodeDbCount: 0
        )
    }

    @Test func twoSevenIsSupported() {
        #expect(capabilities(firmware: "2.7.0").isSupported)
        #expect(capabilities(firmware: "2.7.26.54e0d8d").isSupported)
    }

    @Test func newerThanTwoSevenIsSupported() {
        #expect(capabilities(firmware: "2.8.1-dev").isSupported)
        #expect(capabilities(firmware: "3.0.0").isSupported)
    }

    @Test func olderThanTwoSevenIsNotSupported() {
        #expect(!capabilities(firmware: "2.6.11").isSupported)
        #expect(!capabilities(firmware: "2.5.0").isSupported)
    }

    /// A radio that never said is given the benefit of the doubt rather than blocked.
    @Test func anUnreadableVersionIsNotHeldAgainstTheRadio() {
        #expect(capabilities(firmware: nil).isSupported)
        #expect(capabilities(firmware: "unknown").isSupported)
    }

    /// 2.7 has no XEdDSA, so nothing may depend on a signature being available.
    @Test func signingIsACapabilityNeverAssumedFromTheVersion() {
        #expect(!capabilities(firmware: "2.7.26").supportsSigning)
    }

    @Test func ordersByMajorThenMinorThenPatch() throws {
        let v2726 = try #require(FirmwareVersion.parseOrNull("2.7.26.54e0d8d"))
        let v280 = try #require(FirmwareVersion.parseOrNull("2.8.0.47db0e3"))

        #expect(v2726 < v280)
        #expect(v2726 >= RadioCapabilities.minimumFirmware)
        #expect(try #require(FirmwareVersion.parseOrNull("2.6.9")) < RadioCapabilities.minimumFirmware)
    }
}
