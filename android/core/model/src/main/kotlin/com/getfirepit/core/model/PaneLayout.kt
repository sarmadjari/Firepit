package com.getfirepit.core.model

/** How the user wants a window that is wide enough for two panes used (UX §6.11.4). */
enum class PaneArrangement(val label: String) {
    CHAT_AND_MAP("Chat and map"),
    CHAT_ONLY("Chat only"),
    MAP_ONLY("Map only"),
}

/** Which side the map takes, named by reading direction so a right-to-left language mirrors it. */
enum class MapSide {
    END,
    START,
}

/** The navigation the one-pane layout shows. */
enum class PaneNavigation {
    BAR,
    RAIL,
}

/**
 * A fold the window reports, in the window's own units: dp on Android,
 * points on iOS.
 *
 * [start] and [end] are measured along the width for a fold running top to
 * bottom ([vertical], as in a book) and along the height for one running
 * across (tabletop). They are equal for a crease with nothing in it.
 */
data class WindowFold(
    val vertical: Boolean,
    /** Nothing may be drawn across it: the screen is half-folded, or there is a physical gap. */
    val separating: Boolean,
    val start: Float,
    val end: Float,
)

/** The window Firepit is given, never the device it runs on. */
data class WindowShape(val width: Float, val height: Float, val fold: WindowFold? = null) {
    val isUpright: Boolean get() = height > width
}

/** What the user chose, and where they left the divider (UX §6.11.4). */
data class LayoutChoice(
    val arrangement: PaneArrangement = PaneArrangement.CHAT_AND_MAP,
    val mapSide: MapSide = MapSide.END,
    /** The chat side's share of an upright window's width, or null for the default. */
    val uprightShare: Float? = null,
    /** The chat side's share of a wide window's width, or null for the default. */
    val wideShare: Float? = null,
)

/** Where the conversation and the map go. */
sealed interface PaneLayout {
    /** The phone app: Chats, Map and Settings take turns. */
    data class OnePane(val navigation: PaneNavigation) : PaneLayout

    /** The chat side and the map side next to each other; [mapSide] says which is where. */
    data class SideBySide(
        val chatWidth: Float,
        val mapWidth: Float,
        /** Room between them that holds nothing: a physical gap, or none. */
        val gap: Float,
        val mapSide: MapSide,
        /** A separating fold holds the divider; it cannot be dragged. */
        val dividerLocked: Boolean,
        /** The chat side's shares of the width the divider settles on, smallest first. */
        val anchors: List<Float>,
        /** The chat side is wide enough for the list beside the conversation: three panes in all. */
        val listBesideConversation: Boolean,
    ) : PaneLayout

    /** Map above, conversation below, either side of a fold across the screen. */
    data class Stacked(val mapHeight: Float, val chatHeight: Float, val gap: Float) : PaneLayout
}

/** Where a released divider goes. */
sealed interface DividerSettle {
    /** To this share of the width for the chat side. */
    data class Share(val share: Float) : DividerSettle

    /** The chat side was dragged closed: Map only. */
    data object CloseChat : DividerSettle

    /** The map side was dragged closed: Chat only. */
    data object CloseMap : DividerSettle
}

/**
 * The one rule both apps lay themselves out by (UX §6.11.2).
 *
 * The window decides, never the device: the same rule serves an unfolded
 * phone, a tablet, a desktop window and a slice of a split screen.
 */
object PaneLayouts {
    const val TWO_PANE_MIN_WIDTH = 600f
    const val TWO_PANE_MIN_HEIGHT = 480f
    const val THREE_PANE_MIN_WIDTH = 1200f

    /** The app's width floor, and so the narrowest a chat side may be at the default text size. */
    const val CHAT_MIN = 320f
    const val MAP_MIN = 280f

    /** The list's pane when three show. */
    const val LIST_WIDTH = 320f
    const val CONVERSATION_MIN = 320f

    /** Each half of a half-folded screen needs this much to hold a side of its own. */
    const val STACKED_HALF_MIN = 200f

    /** Text this much larger than default still fits the narrowest chat side. */
    const val TEXT_SCALE_ALLOWANCE = 1.3f

    /** Dragged below this much of its minimum, a pane closes; above it, the divider only settles. */
    const val CLOSE_BELOW = 0.75f

    private val SHARES = listOf(1f / 3f, 1f / 2f, 2f / 3f)

    /** The narrowest the chat side may be at [textScale]: large text needs room before panes split. */
    fun chatMinFor(textScale: Float): Float = CHAT_MIN * maxOf(1f, textScale / TEXT_SCALE_ALLOWANCE)

    fun layoutFor(window: WindowShape, choice: LayoutChoice, chatMin: Float = CHAT_MIN): PaneLayout {
        val navigation = if (window.width >= TWO_PANE_MIN_WIDTH) PaneNavigation.RAIL else PaneNavigation.BAR
        if (choice.arrangement != PaneArrangement.CHAT_AND_MAP) return PaneLayout.OnePane(navigation)

        val fold = window.fold
        if (fold != null && !fold.vertical && fold.separating) {
            val above = fold.start
            val below = window.height - fold.end
            if (above >= STACKED_HALF_MIN && below >= STACKED_HALF_MIN && window.width >= chatMin) {
                return PaneLayout.Stacked(mapHeight = above, chatHeight = below, gap = fold.end - fold.start)
            }
        }

        if (window.width < TWO_PANE_MIN_WIDTH || window.height < TWO_PANE_MIN_HEIGHT) {
            return PaneLayout.OnePane(navigation)
        }

        if (fold != null && fold.vertical && fold.separating) {
            val startSide = fold.start
            val endSide = window.width - fold.end
            val chat = if (choice.mapSide == MapSide.END) startSide else endSide
            val map = if (choice.mapSide == MapSide.END) endSide else startSide
            if (chat < chatMin || map < MAP_MIN) return PaneLayout.OnePane(navigation)
            return PaneLayout.SideBySide(
                chatWidth = chat,
                mapWidth = map,
                gap = fold.end - fold.start,
                mapSide = choice.mapSide,
                dividerLocked = true,
                anchors = emptyList(),
                listBesideConversation = listFits(window, chat),
            )
        }

        if (window.width < chatMin + MAP_MIN) return PaneLayout.OnePane(navigation)

        val crease = creaseShare(window, choice.mapSide)
        val saved = if (window.isUpright) choice.uprightShare else choice.wideShare
        val share = saved ?: crease ?: defaultShare(window)
        val chat = (share * window.width).coerceIn(chatMin, window.width - MAP_MIN)
        val anchors = (SHARES + listOfNotNull(crease))
            .filter { it * window.width >= chatMin && (1 - it) * window.width >= MAP_MIN }
            .distinct()
            .sorted()
        return PaneLayout.SideBySide(
            chatWidth = chat,
            mapWidth = window.width - chat,
            gap = 0f,
            mapSide = choice.mapSide,
            dividerLocked = false,
            anchors = anchors,
            listBesideConversation = listFits(window, chat),
        )
    }

    /**
     * Where a divider released at [chatWidth] goes: onto the nearest anchor, or
     * closing a pane dragged well below its minimum.
     */
    fun settle(chatWidth: Float, window: WindowShape, layout: PaneLayout.SideBySide, chatMin: Float = CHAT_MIN): DividerSettle {
        val mapWidth = window.width - chatWidth
        if (chatWidth < chatMin * CLOSE_BELOW) return DividerSettle.CloseChat
        if (mapWidth < MAP_MIN * CLOSE_BELOW) return DividerSettle.CloseMap
        val share = chatWidth / window.width
        val nearest = layout.anchors.minByOrNull { kotlin.math.abs(it - share) }
        if (nearest == null) {
            // In widths, not shares: at the exact minimum the two shares round past each other.
            val widest = maxOf(chatMin, window.width - MAP_MIN)
            return DividerSettle.Share(chatWidth.coerceIn(chatMin, widest) / window.width)
        }
        return DividerSettle.Share(nearest)
    }

    private fun listFits(window: WindowShape, chat: Float): Boolean =
        window.width >= THREE_PANE_MIN_WIDTH && chat >= LIST_WIDTH + CONVERSATION_MIN

    /** The chat side's share that puts the divider on a crease the window lies flat across. */
    private fun creaseShare(window: WindowShape, mapSide: MapSide): Float? {
        val fold = window.fold?.takeIf { it.vertical && !it.separating } ?: return null
        val middle = (fold.start + fold.end) / 2
        return if (mapSide == MapSide.END) middle / window.width else 1 - middle / window.width
    }

    /** Half each; from three panes, the list's own width plus half of the rest. */
    private fun defaultShare(window: WindowShape): Float =
        if (window.width >= THREE_PANE_MIN_WIDTH) {
            (LIST_WIDTH + (window.width - LIST_WIDTH) / 2) / window.width
        } else {
            0.5f
        }
}
