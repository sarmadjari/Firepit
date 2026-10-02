import FirepitModel
import SwiftUI
import Testing
import UIKit

@testable import Firepit

/// The design system against android/core/designsystem: the same tokens, and the same per-person and per-room picks,
/// so a room looks the same on an iPhone and an Android phone.
@Suite("Design system")
struct DesignSystemTests {
    /// FirepitColors.kt (EmberLightColors, EmberDarkColors, EmberPalette): name, token, light, dark.
    static let tokens: [(String, Color, UInt32, UInt32)] = [
        ("primary", FirepitColors.primary, 0xC2410C, 0xF0875A),
        ("onPrimary", FirepitColors.onPrimary, 0xFFFFFF, 0x3B1400),
        ("surface", FirepitColors.surface, 0xFAF7F3, 0x141210),
        ("surface2", FirepitColors.surface2, 0xFFFFFF, 0x1E1B18),
        ("bubbleOut", FirepitColors.bubbleOut, 0xFBE3D6, 0x3A2418),
        ("bubbleIn", FirepitColors.bubbleIn, 0xFFFFFF, 0x24211E),
        ("textPrimary", FirepitColors.textPrimary, 0x1A1614, 0xF1ECE7),
        ("textSecondary", FirepitColors.textSecondary, 0x6B625C, 0xA39C95),
        ("outline", FirepitColors.outline, 0xE8E0D9, 0x2E2926),
        ("live", FirepitColors.live, 0x2BB673, 0x4ED69A),
        ("stale", FirepitColors.stale, 0xA39E98, 0x6F6963),
        ("warn", FirepitColors.warn, 0x9A6B00, 0xF2C94C),
        ("danger", FirepitColors.danger, 0xC62B4A, 0xF27D8E),
        ("infra", FirepitColors.infra, 0x4A5B8C, 0x93A6DF),
        ("mapGround", FirepitColors.mapGround, 0xEEEAE4, 0x1C1A17),
        ("mapRoad", FirepitColors.mapRoad, 0xFFFFFF, 0x2C2925),
        ("mapWater", FirepitColors.mapWater, 0xD5E5F0, 0x1E2C38),
        ("mapPark", FirepitColors.mapPark, 0xD6E3CF, 0x22301F),
    ]

    @Test("Ember tokens match Android in both themes")
    func tokensMatchAndroid() {
        for (name, color, light, dark) in Self.tokens {
            #expect(resolved(color, .light) == light, "\(name) light")
            #expect(resolved(color, .dark) == dark, "\(name) dark")
        }
    }

    @Test("Text reaches 4.5:1 on every surface", arguments: [UIUserInterfaceStyle.light, .dark])
    func textContrast(style: UIUserInterfaceStyle) {
        for text in [FirepitColors.textPrimary, FirepitColors.textSecondary] {
            for surface in [
                FirepitColors.surface, FirepitColors.surface2, FirepitColors.bubbleIn,
                FirepitColors.bubbleOut,
            ] {
                #expect(contrast(resolved(text, style), resolved(surface, style)) >= 4.5)
            }
        }
        #expect(contrast(resolved(FirepitColors.onPrimary, style), resolved(FirepitColors.primary, style)) >= 4.5)
    }

    @Test("Identity slot is floorMod(nodeNum, 12) on the signed node number, as on Android")
    func identitySlot() {
        #expect(IdentityColors.slot(for: 0) == 0)
        #expect(IdentityColors.slot(for: 13) == 1)
        #expect(IdentityColors.slot(for: -1) == 11)
        #expect(IdentityColors.slot(for: Int32.min) == 4)
        #expect(IdentityColors.slot(for: Int32.max) == 7)
        // A node number above 2^31 arrives as a negative Int32 on both platforms (unsigned it would be slot 11).
        #expect(IdentityColors.slot(for: Int32(bitPattern: 0xDEAD_BEEF)) == 7)
    }

    @Test("Identity colours follow hue = slot·30°, S 50 %, L 42 % light / 64 % dark")
    func identityColours() {
        for slot in 0..<IdentityColors.slots {
            let hue = Double(slot) * 30
            let color = IdentityColors.color(forSlot: slot)
            #expect(resolved(color, .light) == RGB(hue: hue, saturation: 0.5, lightness: 0.42).hex)
            #expect(resolved(color, .dark) == RGB(hue: hue, saturation: 0.5, lightness: 0.64).hex)
        }
        #expect(RGB(hue: 0, saturation: 0.5, lightness: 0.42).hex == 0xA13636)
        #expect(RGB(hue: 210, saturation: 0.5, lightness: 0.42).hex == 0x366BA1)
    }

    @Test("A personal pick overrides the derived colour")
    func identityOverride() {
        let nodeNum: Int32 = 5
        let picked = IdentityColors.color(for: nodeNum, slot: 8)
        #expect(resolved(picked, .light) == resolved(IdentityColors.color(forSlot: 8), .light))
        #expect(
            resolved(IdentityColors.color(for: nodeNum), .light)
                == resolved(IdentityColors.color(forSlot: 5), .light))
    }

    @Test("Avatar labels are white in light mode and a deep tint of the hue in dark mode")
    func identityLabels() {
        for nodeNum: Int32 in [0, 7, -3] {
            let label = IdentityColors.onColor(for: nodeNum)
            let hue = IdentityColors.hue(ofSlot: IdentityColors.slot(for: nodeNum))
            #expect(resolved(label, .light) == 0xFFFFFF)
            #expect(resolved(label, .dark) == RGB(hue: hue, saturation: 0.5, lightness: 0.16).hex)
        }
    }

    @Test("The colour picker offers the ten distinguishable slots")
    func identityChoices() {
        #expect(IdentityColors.choices == [0, 1, 3, 4, 5, 6, 7, 8, 9, 10])
    }

    @Test("Room icons keep Android's order")
    func roomIconOrder() {
        #expect(RoomIcon.allCases.map(\.label) == ["Tent", "Trail", "Car", "Music", "Flag", "House", "Star", "Heart"])
        #expect(RoomIcon.default == .tent)
    }

    @Test("Room icon is floorMod(roomId, 8), so every member sees the same one")
    func roomIconPick() {
        #expect(RoomIcon.forRoomId(0) == .tent)
        #expect(RoomIcon.forRoomId(9) == .trail)
        #expect(RoomIcon.forRoomId(-1) == .heart)
        #expect(RoomIcon.forRoomId(Int32.min) == .tent)
        #expect(RoomIcon.forRoomId(Int32.max) == .heart)
    }

    @Test("Every tick has Android's spoken label, and received messages show none")
    func tickLabels() {
        let expected: [MessageStatus: String?] = [
            .queued: "Sending",
            .sentToNode: "Sent to your node",
            .unknown: "Sent to your node",
            .reachedMesh: "Heard by at least one node",
            .delivered: "Delivered to their node",
            .unheard: "No node heard this",
            .failed: "Failed, tap to retry",
            .received: nil,
        ]
        for status in MessageStatus.allCases {
            #expect(status.label.map { String(localized: $0) } == expected[status] ?? nil, "\(status)")
        }
    }

    @Test("Only mesh-heard and delivered ticks are green; failures are red")
    func tickTints() {
        #expect(MessageStatus.reachedMesh.tint == FirepitColors.live)
        #expect(MessageStatus.delivered.tint == FirepitColors.live)
        #expect(MessageStatus.failed.tint == FirepitColors.danger)
        for status: MessageStatus in [.queued, .sentToNode, .unknown, .unheard] {
            #expect(status.tint == FirepitColors.textSecondary)
        }
    }

    @Test("Hex round-trips through RGB")
    func hexRoundTrip() {
        for hex: UInt32 in [0x000000, 0xFFFFFF, 0xC2410C, 0x3B1400] {
            #expect(RGB(hex: hex).hex == hex)
        }
    }

    @Test("The mark is the app icon's flame, point for point")
    func markIsTheArtworksFlame() throws {
        let artwork = try #require(Self.repositoryFile("design/artwork/icon-app.svg"), "design/artwork/icon-app.svg")
        let svg = try String(contentsOf: artwork, encoding: .utf8)
        // Every point of every path the artwork draws, in the order the path data lists them.
        let drawn = svg.matches(of: /<path d="([^"]+)"/).map { match in
            let numbers = String(match.1).matches(of: /-?\d*\.?\d+/).compactMap { Double($0.0) }
            return stride(from: 0, to: numbers.count - 1, by: 2).map { CGPoint(x: numbers[$0], y: numbers[$0 + 1]) }
        }
        var mark: [CGPoint] = []
        FirepitMark.flame.forEach { element in
            switch element {
            case .move(let point): mark.append(point)
            case .curve(let point, let control1, let control2): mark += [control1, control2, point]
            case .closeSubpath: break
            case .line, .quadCurve: Issue.record("the artwork's flame is made of curves only")
            }
        }
        // SwiftUI keeps path coordinates as 32-bit floats, so they come back within a ten-thousandth, not exactly.
        func same(_ drawn: [CGPoint]) -> Bool {
            drawn.count == mark.count
                && zip(drawn, mark).allSatisfy { abs($0.x - $1.x) < 0.001 && abs($0.y - $1.y) < 0.001 }
        }
        #expect(drawn.contains(where: same), "FirepitMark.flame no longer matches the flame in icon-app.svg")
    }

    /// A file in the repository, found from this test's own source path: the bundle runs in the simulator, whose
    /// working directory is not the repository.
    private static func repositoryFile(_ path: String, sourceFile: String = #filePath) -> URL? {
        var directory = URL(fileURLWithPath: sourceFile).deletingLastPathComponent()
        while directory.path != "/" {
            let candidate = directory.appending(path: path)
            if FileManager.default.fileExists(atPath: candidate.path) { return candidate }
            directory = directory.deletingLastPathComponent()
        }
        return nil
    }

    private func resolved(_ color: Color, _ style: UIUserInterfaceStyle) -> UInt32 {
        var red: CGFloat = 0
        var green: CGFloat = 0
        var blue: CGFloat = 0
        var alpha: CGFloat = 0
        UIColor(color).resolvedColor(with: UITraitCollection(userInterfaceStyle: style))
            .getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        return RGB(red: red, green: green, blue: blue).hex
    }

    /// WCAG 2 contrast ratio between two sRGB colours.
    private func contrast(_ first: UInt32, _ second: UInt32) -> Double {
        func luminance(_ hex: UInt32) -> Double {
            let rgb = RGB(hex: hex)
            func linear(_ c: Double) -> Double { c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4) }
            return 0.2126 * linear(rgb.red) + 0.7152 * linear(rgb.green) + 0.0722 * linear(rgb.blue)
        }
        let (a, b) = (luminance(first), luminance(second))
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }
}
