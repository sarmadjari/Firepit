import FirepitModel
import SwiftUI
import UIKit

/// What a screen needs to know about the wide-screen layout around it (UX §6.11). Ported from
/// android/core/designsystem/…/adaptive/WideScreen.kt.
///
/// Provided by the shell; nil on a window too narrow ever to show two panes, so phone screens draw nothing extra.
struct WideScreenControls {
    /// The window could show two panes: the layout menu has something to offer.
    let canSplit: Bool
    let arrangement: PaneArrangement
    let onArrange: (PaneArrangement) -> Void
    /// Nil while two panes do not sit side by side, so swapping would mean nothing.
    let onSwapSides: (() -> Void)?
    /// Set while two panes show and the tab bar does not: Settings opens from the chat side.
    let onOpenSettings: (() -> Void)?
}

extension EnvironmentValues {
    @Entry var wideScreen: WideScreenControls?
    /// The map sits beside the chat, so a conversation can say what it shares with it.
    @Entry var besideMap = false
    /// The chat side is wide enough to keep its list beside the conversation: three panes (UX §6.11.3).
    @Entry var listBesideConversation = false
}

/// The layout button ◫ and its menu: the three arrangements with the current one ticked, then Swap sides while two
/// panes sit side by side. Draws nothing on a window that can never split.
struct LayoutMenu<Label: View>: View {
    @Environment(\.wideScreen) private var controls
    @ViewBuilder let label: () -> Label

    var body: some View {
        if let controls, controls.canSplit {
            Menu {
                Picker(
                    selection: Binding(get: { controls.arrangement }, set: { controls.onArrange($0) })
                ) {
                    ForEach(PaneArrangement.allCases, id: \.self) { option in
                        Text(verbatim: option.label).tag(option)
                    }
                } label: {
                    EmptyView()
                }
                .pickerStyle(.inline)
                if controls.arrangement == .chatAndMap, let swap = controls.onSwapSides {
                    Divider()
                    Button("Swap sides", action: swap)
                }
            } label: {
                label()
            }
            .accessibilityLabel(Text("Layout"))
        }
    }
}

extension LayoutMenu where Label == Image {
    /// The menu as a toolbar button.
    init() {
        self.init { Image(systemName: "rectangle.split.2x1") }
    }
}

/// ⚙ for the chat list's toolbar, only while two panes leave no tab bar to carry Settings (UX §6.11.7).
struct WideScreenSettingsButton: View {
    @Environment(\.wideScreen) private var controls

    var body: some View {
        if let open = controls?.onOpenSettings {
            Button(action: open) { Image(icon: .settings) }
                .accessibilityLabel(Text("Settings"))
        }
    }
}

/// The user's wide-screen choices, on this device only (UX §6.11.4). Ported from
/// android/app/…/settings/LayoutPreferences.kt; `@AppStorage` keeps them, and every view reading them updates.
struct LayoutPreferences: DynamicProperty {
    @AppStorage("layout.arrangement") private var arrangement = PaneArrangement.chatAndMap.rawValue
    @AppStorage("layout.mapSide") private var mapSide = MapSide.end.rawValue
    @AppStorage("layout.uprightShare") private var uprightShare = Self.unset
    @AppStorage("layout.wideShare") private var wideShare = Self.unset

    var choice: LayoutChoice {
        LayoutChoice(
            arrangement: PaneArrangement(rawValue: arrangement) ?? .chatAndMap,
            mapSide: MapSide(rawValue: mapSide) ?? .end,
            uprightShare: uprightShare == Self.unset ? nil : uprightShare,
            wideShare: wideShare == Self.unset ? nil : wideShare
        )
    }

    func setArrangement(_ next: PaneArrangement) { arrangement = next.rawValue }

    func setMapSide(_ side: MapSide) { mapSide = side.rawValue }

    func swapSides() { mapSide = (choice.mapSide == .end ? MapSide.start : .end).rawValue }

    func setShare(upright: Bool, _ share: Double) {
        if upright { uprightShare = share } else { wideShare = share }
    }

    func resetDivider() {
        uprightShare = Self.unset
        wideShare = Self.unset
    }

    private static let unset = -1.0
}

extension DynamicTypeSize {
    /// How much larger than the default this text size draws body text, for the chat side's minimum width
    /// (`PaneLayouts.chatMinFor`).
    var textScale: Double {
        let category = UIContentSizeCategory(self)
        let metrics = UIFontMetrics(forTextStyle: .body)
        let traits = UITraitCollection(preferredContentSizeCategory: category)
        return Double(metrics.scaledValue(for: 17, compatibleWith: traits) / 17)
    }
}

extension UIContentSizeCategory {
    fileprivate init(_ size: DynamicTypeSize) {
        switch size {
        case .xSmall: self = .extraSmall
        case .small: self = .small
        case .medium: self = .medium
        case .large: self = .large
        case .xLarge: self = .extraLarge
        case .xxLarge: self = .extraExtraLarge
        case .xxxLarge: self = .extraExtraExtraLarge
        case .accessibility1: self = .accessibilityMedium
        case .accessibility2: self = .accessibilityLarge
        case .accessibility3: self = .accessibilityExtraLarge
        case .accessibility4: self = .accessibilityExtraExtraLarge
        case .accessibility5: self = .accessibilityExtraExtraExtraLarge
        @unknown default: self = .large
        }
    }
}
