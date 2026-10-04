import Testing

@testable import FirepitModel

/// The layout rule against the windows of the 2026 devices and every boundary (UX §6.11.2). Ported from
/// android/core/model PaneLayoutTest: the same cases, so both apps make the same decisions.
@Suite("Pane layout")
struct PaneLayoutTests {
    private let chatAndMap = LayoutChoice()

    private func layout(
        _ width: Double, _ height: Double, _ fold: WindowFold? = nil, _ choice: LayoutChoice = LayoutChoice(),
        chatMin: Double = PaneLayouts.chatMin
    ) -> PaneLayout {
        PaneLayouts.layoutFor(WindowShape(width: width, height: height, fold: fold), choice, chatMin: chatMin)
    }

    private func crease(_ at: Double) -> WindowFold {
        WindowFold(vertical: true, separating: false, start: at, end: at)
    }
    private func book(_ at: Double) -> WindowFold { WindowFold(vertical: true, separating: true, start: at, end: at) }
    private func tabletop(_ at: Double) -> WindowFold {
        WindowFold(vertical: false, separating: true, start: at, end: at)
    }

    private func sides(_ layout: PaneLayout) throws -> PaneLayout.SideBySide {
        guard case .sideBySide(let sides) = layout else {
            Issue.record("expected side by side, got \(layout)")
            throw CancellationError()
        }
        return sides
    }

    private func expectWidths(_ layout: PaneLayout, _ chat: Double, _ map: Double) throws {
        let sides = try sides(layout)
        #expect(abs(sides.chatWidth - chat) < 0.5)
        #expect(abs(sides.mapWidth - map) < 0.5)
    }

    @Test func aPhoneIsThePhoneAppWithTheBarUprightAndTheRailOnItsSide() {
        #expect(layout(412, 915) == .onePane(navigation: .bar))
        #expect(layout(915, 412) == .onePane(navigation: .rail))
    }

    @Test func coverScreensAreThePhoneApp() {
        #expect(layout(411, 960) == .onePane(navigation: .bar))
        #expect(layout(466, 678) == .onePane(navigation: .bar))
        // The wide Fold8's cover screen on its side is wide enough but too short.
        #expect(layout(700, 440) == .onePane(navigation: .rail))
    }

    @Test func anUnfoldedFold8UltraLyingFlatSplitsOnItsCrease() throws {
        let unfolded = layout(752, 835, crease(376))
        try expectWidths(unfolded, 376, 376)
        let sides = try sides(unfolded)
        #expect(!sides.dividerLocked)
        #expect(sides.anchors == [0.5])
        #expect(!sides.listBesideConversation)
    }

    @Test func halfFoldedLikeABookTheDividerIsLockedOnTheFold() throws {
        let sides = try sides(layout(752, 835, book(376)))
        #expect(abs(sides.chatWidth - 376) < 0.5)
        #expect(abs(sides.mapWidth - 376) < 0.5)
        #expect(sides.dividerLocked)
        #expect(sides.anchors.isEmpty)
    }

    @Test func aLargerDisplaySizeStillSplitsOnTheFold() throws {
        try expectWidths(layout(700, 780, book(350)), 350, 350)
    }

    @Test func theWideFold8HeldLandscapeGetsTwoPanesAndTwoPlacesToSettle() throws {
        let sides = try sides(layout(930, 700, crease(465)))
        #expect(abs(sides.chatWidth - 465) < 0.5)
        #expect(sides.anchors == [0.5, 2.0 / 3.0])
    }

    @Test func aFoldLyingFlatAcrossTheScreenDoesNotStackThePanes() throws {
        let flatAcross = WindowFold(vertical: false, separating: false, start: 465, end: 465)
        try expectWidths(layout(700, 930, flatAcross), 350, 350)
    }

    @Test func halfFoldedAcrossTheScreenTheMapGoesAboveAndTheChatBelow() {
        #expect(layout(412, 915, tabletop(457.5)) == .stacked(mapHeight: 457.5, chatHeight: 457.5, gap: 0))
        #expect(layout(835, 752, tabletop(376)) == .stacked(mapHeight: 376, chatHeight: 376, gap: 0))
    }

    @Test func tabletopHalvesTooSmallForASideEachAreThePhoneApp() {
        #expect(layout(360, 380, tabletop(190)) == .onePane(navigation: .bar))
    }

    @Test func chatOnlyAndMapOnlyAreOnePaneHoweverWide() {
        let chatOnly = LayoutChoice(arrangement: .chatOnly)
        let mapOnly = LayoutChoice(arrangement: .mapOnly)
        #expect(layout(752, 835, crease(376), chatOnly) == .onePane(navigation: .rail))
        #expect(layout(1280, 800, nil, mapOnly) == .onePane(navigation: .rail))
        #expect(layout(412, 915, tabletop(457.5), chatOnly) == .onePane(navigation: .bar))
    }

    @Test func aTabletOnItsSideShowsThreePanes() throws {
        let sides = try sides(layout(1280, 800))
        #expect(abs(sides.chatWidth - 800) < 0.5)
        #expect(abs(sides.mapWidth - 480) < 0.5)
        #expect(sides.listBesideConversation)
    }

    @Test func aTabletUprightShowsTwo() throws {
        let sides = try sides(layout(800, 1280))
        #expect(abs(sides.chatWidth - 400) < 0.5)
        #expect(!sides.listBesideConversation)
    }

    @Test func threePanesStartAt1200Wide() throws {
        #expect(try sides(layout(1200, 800)).listBesideConversation)
        #expect(try !sides(layout(1199, 800)).listBesideConversation)
    }

    @Test func twoPanesStartAt600WideAnd480Tall() throws {
        try expectWidths(layout(600, 480), 320, 280)
        #expect(layout(599, 800) == .onePane(navigation: .bar))
        #expect(layout(600, 479) == .onePane(navigation: .rail))
    }

    @Test func atExactlyTheMinimumThereIsNowhereForTheDividerToSettleButWhereItIs() throws {
        #expect(try sides(layout(600, 480)).anchors.isEmpty)
    }

    @Test func largeTextKeepsOnePaneUntilTheChatSideFits() throws {
        let big = PaneLayouts.chatMinFor(2)
        #expect(layout(752, 835, crease(376), chatMin: big) == .onePane(navigation: .rail))
        #expect(layout(752, 835, book(376), chatMin: big) == .onePane(navigation: .rail))
        try expectWidths(layout(752, 835, crease(376), chatMin: PaneLayouts.chatMinFor(1.3)), 376, 376)
    }

    @Test func theNarrowestChatSideGrowsOnlyBeyondTheAllowanceForLargerText() {
        #expect(abs(PaneLayouts.chatMinFor(1) - 320) < 0.01)
        #expect(abs(PaneLayouts.chatMinFor(1.3) - 320) < 0.01)
        #expect(abs(PaneLayouts.chatMinFor(2.6) - 640) < 0.01)
    }

    @Test func theMapOnTheStartSideMirrorsTheSplit() throws {
        let start = LayoutChoice(mapSide: .start)
        try expectWidths(layout(800, 835, crease(300), start), 500, 300)
        try expectWidths(layout(800, 835, crease(300)), 320, 480)
        let locked = try sides(
            layout(1114, 720, WindowFold(vertical: true, separating: true, start: 540, end: 574), start))
        #expect(abs(locked.chatWidth - 540) < 0.5)
        #expect(locked.mapSide == .start)
    }

    @Test func aPhysicalGapBetweenTwoScreensHoldsNothing() throws {
        let sides = try sides(layout(1114, 720, WindowFold(vertical: true, separating: true, start: 540, end: 574)))
        #expect(abs(sides.chatWidth - 540) < 0.5)
        #expect(abs(sides.mapWidth - 540) < 0.5)
        #expect(abs(sides.gap - 34) < 0.5)
    }

    @Test func whereTheUserLeftTheDividerIsKeptApartForUprightAndWideWindows() throws {
        let choice = LayoutChoice(uprightShare: 0.6, wideShare: 0.4)
        try expectWidths(layout(752, 835, crease(376), choice), 451.2, 300.8)
        try expectWidths(layout(930, 700, crease(465), choice), 372, 558)
    }

    @Test func aRememberedShareNeverLeavesEitherSideBelowItsMinimum() throws {
        try expectWidths(layout(752, 835, nil, LayoutChoice(uprightShare: 0.95)), 472, 280)
        try expectWidths(layout(752, 835, nil, LayoutChoice(uprightShare: 0.05)), 320, 432)
    }

    @Test func aFoldThatSeparatesIsNotAReasonToIgnoreTheMinimums() {
        #expect(layout(620, 835, book(300)) == .onePane(navigation: .rail))
    }

    @Test func aReleasedDividerSettlesOnTheNearestAnchor() throws {
        let window = WindowShape(width: 930, height: 700, fold: crease(465))
        let sides = try sides(PaneLayouts.layoutFor(window, chatAndMap))
        #expect(PaneLayouts.settle(500, window, sides) == .share(0.5))
        #expect(PaneLayouts.settle(600, window, sides) == .share(2.0 / 3.0))
    }

    @Test func draggedWellBelowItsMinimumAPaneClosesJustBelowItOnlySettles() throws {
        let window = WindowShape(width: 930, height: 700, fold: crease(465))
        let sides = try sides(PaneLayouts.layoutFor(window, chatAndMap))
        #expect(PaneLayouts.settle(200, window, sides) == .closeChat)
        #expect(PaneLayouts.settle(930 - 150, window, sides) == .closeMap)
        #expect(PaneLayouts.settle(300, window, sides) == .share(0.5))
    }

    @Test func withoutAnchorsAReleasedDividerStaysWhereItWasLetGoWithinTheMinimums() throws {
        let window = WindowShape(width: 600, height: 480)
        let sides = try sides(PaneLayouts.layoutFor(window, chatAndMap))
        #expect(PaneLayouts.settle(330, window, sides) == .share(320.0 / 600.0))
    }
}
