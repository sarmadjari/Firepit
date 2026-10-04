import Foundation

/// How the user wants a window that is wide enough for two panes used (UX §6.11.4). Android: `PaneArrangement`.
public enum PaneArrangement: String, CaseIterable, Sendable {
    case chatAndMap
    case chatOnly
    case mapOnly

    public var label: String {
        switch self {
        case .chatAndMap: "Chat and map"
        case .chatOnly: "Chat only"
        case .mapOnly: "Map only"
        }
    }
}

/// Which side the map takes, named by reading direction so a right-to-left language mirrors it.
public enum MapSide: String, CaseIterable, Sendable {
    case end
    case start
}

/// The navigation the one-pane layout shows.
public enum PaneNavigation: Sendable {
    case bar
    case rail
}

/// A fold the window reports, in the window's own units: points on iOS, dp on Android.
///
/// `start` and `end` are measured along the width for a fold running top to bottom (`vertical`, as in a book) and
/// along the height for one running across (tabletop). They are equal for a crease with nothing in it.
public struct WindowFold: Equatable, Sendable {
    public let vertical: Bool
    /// Nothing may be drawn across it: the screen is half-folded, or there is a physical gap.
    public let separating: Bool
    public let start: Double
    public let end: Double

    public init(vertical: Bool, separating: Bool, start: Double, end: Double) {
        self.vertical = vertical
        self.separating = separating
        self.start = start
        self.end = end
    }
}

/// The window Firepit is given, never the device it runs on.
public struct WindowShape: Equatable, Sendable {
    public let width: Double
    public let height: Double
    public let fold: WindowFold?

    public init(width: Double, height: Double, fold: WindowFold? = nil) {
        self.width = width
        self.height = height
        self.fold = fold
    }

    public var isUpright: Bool { height > width }
}

/// What the user chose, and where they left the divider (UX §6.11.4).
public struct LayoutChoice: Equatable, Sendable {
    public var arrangement: PaneArrangement
    public var mapSide: MapSide
    /// The chat side's share of an upright window's width, or nil for the default.
    public var uprightShare: Double?
    /// The chat side's share of a wide window's width, or nil for the default.
    public var wideShare: Double?

    public init(
        arrangement: PaneArrangement = .chatAndMap,
        mapSide: MapSide = .end,
        uprightShare: Double? = nil,
        wideShare: Double? = nil
    ) {
        self.arrangement = arrangement
        self.mapSide = mapSide
        self.uprightShare = uprightShare
        self.wideShare = wideShare
    }
}

/// Where the conversation and the map go.
public enum PaneLayout: Equatable, Sendable {
    /// The phone app: Chats, Map and Settings take turns.
    case onePane(navigation: PaneNavigation)
    /// The chat side and the map side next to each other; `mapSide` says which is where.
    case sideBySide(SideBySide)
    /// Map above, conversation below, either side of a fold across the screen.
    case stacked(mapHeight: Double, chatHeight: Double, gap: Double)

    public struct SideBySide: Equatable, Sendable {
        public let chatWidth: Double
        public let mapWidth: Double
        /// Room between them that holds nothing: a physical gap, or none.
        public let gap: Double
        public let mapSide: MapSide
        /// A separating fold holds the divider; it cannot be dragged.
        public let dividerLocked: Bool
        /// The chat side's shares of the width the divider settles on, smallest first.
        public let anchors: [Double]
        /// The chat side is wide enough for the list beside the conversation: three panes in all.
        public let listBesideConversation: Bool

        public init(
            chatWidth: Double, mapWidth: Double, gap: Double, mapSide: MapSide, dividerLocked: Bool,
            anchors: [Double], listBesideConversation: Bool
        ) {
            self.chatWidth = chatWidth
            self.mapWidth = mapWidth
            self.gap = gap
            self.mapSide = mapSide
            self.dividerLocked = dividerLocked
            self.anchors = anchors
            self.listBesideConversation = listBesideConversation
        }
    }
}

/// Where a released divider goes.
public enum DividerSettle: Equatable, Sendable {
    /// To this share of the width for the chat side.
    case share(Double)
    /// The chat side was dragged closed: Map only.
    case closeChat
    /// The map side was dragged closed: Chat only.
    case closeMap
}

/// The one rule both apps lay themselves out by (UX §6.11.2). Android: `PaneLayouts`.
///
/// The window decides, never the device: the same rule serves an unfolded phone, a tablet, a desktop window and a
/// slice of a split screen.
public enum PaneLayouts {
    public static let twoPaneMinWidth = 600.0
    public static let twoPaneMinHeight = 480.0
    public static let threePaneMinWidth = 1200.0

    /// The app's width floor, and so the narrowest a chat side may be at the default text size.
    public static let chatMin = 320.0
    public static let mapMin = 280.0

    /// The list's pane when three show.
    public static let listWidth = 320.0
    public static let conversationMin = 320.0

    /// Each half of a half-folded screen needs this much to hold a side of its own.
    public static let stackedHalfMin = 200.0

    /// Text this much larger than default still fits the narrowest chat side.
    public static let textScaleAllowance = 1.3

    /// Dragged below this much of its minimum, a pane closes; above it, the divider only settles.
    public static let closeBelow = 0.75

    private static let shares = [1.0 / 3.0, 1.0 / 2.0, 2.0 / 3.0]

    /// The narrowest the chat side may be at `textScale`: large text needs room before panes split.
    public static func chatMinFor(_ textScale: Double) -> Double {
        chatMin * max(1, textScale / textScaleAllowance)
    }

    public static func layoutFor(_ window: WindowShape, _ choice: LayoutChoice, chatMin: Double = chatMin) -> PaneLayout
    {
        let navigation: PaneNavigation = window.width >= twoPaneMinWidth ? .rail : .bar
        if choice.arrangement != .chatAndMap {
            return .onePane(navigation: navigation)
        }

        let fold = window.fold
        if let fold, !fold.vertical, fold.separating {
            let above = fold.start
            let below = window.height - fold.end
            if above >= stackedHalfMin, below >= stackedHalfMin, window.width >= chatMin {
                return .stacked(mapHeight: above, chatHeight: below, gap: fold.end - fold.start)
            }
        }

        if window.width < twoPaneMinWidth || window.height < twoPaneMinHeight {
            return .onePane(navigation: navigation)
        }

        if let fold, fold.vertical, fold.separating {
            let startSide = fold.start
            let endSide = window.width - fold.end
            let chat = choice.mapSide == .end ? startSide : endSide
            let map = choice.mapSide == .end ? endSide : startSide
            if chat < chatMin || map < mapMin {
                return .onePane(navigation: navigation)
            }
            return .sideBySide(
                PaneLayout.SideBySide(
                    chatWidth: chat, mapWidth: map, gap: fold.end - fold.start, mapSide: choice.mapSide,
                    dividerLocked: true, anchors: [], listBesideConversation: listFits(window, chat)))
        }

        if window.width < chatMin + mapMin {
            return .onePane(navigation: navigation)
        }

        let crease = creaseShare(window, choice.mapSide)
        let saved = window.isUpright ? choice.uprightShare : choice.wideShare
        let share = saved ?? crease ?? defaultShare(window)
        let chat = min(max(share * window.width, chatMin), window.width - mapMin)
        var anchors: [Double] = []
        for anchor in (shares + [crease].compactMap { $0 })
        where anchor * window.width >= chatMin && (1 - anchor) * window.width >= mapMin
            && !anchors.contains(anchor)
        {
            anchors.append(anchor)
        }
        return .sideBySide(
            PaneLayout.SideBySide(
                chatWidth: chat, mapWidth: window.width - chat, gap: 0, mapSide: choice.mapSide,
                dividerLocked: false, anchors: anchors.sorted(), listBesideConversation: listFits(window, chat)))
    }

    /// Where a divider released at `chatWidth` goes: onto the nearest anchor, or closing a pane dragged well below
    /// its minimum.
    public static func settle(
        _ chatWidth: Double, _ window: WindowShape, _ layout: PaneLayout.SideBySide, chatMin: Double = chatMin
    ) -> DividerSettle {
        let mapWidth = window.width - chatWidth
        if chatWidth < chatMin * closeBelow {
            return .closeChat
        }
        if mapWidth < mapMin * closeBelow {
            return .closeMap
        }
        let share = chatWidth / window.width
        guard let nearest = layout.anchors.min(by: { abs($0 - share) < abs($1 - share) }) else {
            // In widths, not shares: at the exact minimum the two shares round past each other.
            let widest = max(chatMin, window.width - mapMin)
            return .share(min(max(chatWidth, chatMin), widest) / window.width)
        }
        return .share(nearest)
    }

    private static func listFits(_ window: WindowShape, _ chat: Double) -> Bool {
        window.width >= threePaneMinWidth && chat >= listWidth + conversationMin
    }

    /// The chat side's share that puts the divider on a crease the window lies flat across.
    private static func creaseShare(_ window: WindowShape, _ mapSide: MapSide) -> Double? {
        guard let fold = window.fold, fold.vertical, !fold.separating else {
            return nil
        }
        let middle = (fold.start + fold.end) / 2
        return mapSide == .end ? middle / window.width : 1 - middle / window.width
    }

    /// Half each; from three panes, the list's own width plus half of the rest.
    private static func defaultShare(_ window: WindowShape) -> Double {
        window.width >= threePaneMinWidth ? (listWidth + (window.width - listWidth) / 2) / window.width : 0.5
    }
}
