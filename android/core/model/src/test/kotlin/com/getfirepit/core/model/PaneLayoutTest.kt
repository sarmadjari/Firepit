package com.getfirepit.core.model

import com.getfirepit.core.model.PaneLayout.OnePane
import com.getfirepit.core.model.PaneLayout.SideBySide
import com.getfirepit.core.model.PaneLayout.Stacked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layout rule against the windows of the 2026 devices and every boundary
 * (UX §6.11.2). The iOS port runs the same cases.
 */
class PaneLayoutTest {

    private val chatAndMap = LayoutChoice()

    private fun layout(
        width: Float,
        height: Float,
        fold: WindowFold? = null,
        choice: LayoutChoice = chatAndMap,
        chatMin: Float = PaneLayouts.CHAT_MIN,
    ) = PaneLayouts.layoutFor(WindowShape(width, height, fold), choice, chatMin)

    private fun crease(at: Float) = WindowFold(vertical = true, separating = false, start = at, end = at)

    private fun book(at: Float) = WindowFold(vertical = true, separating = true, start = at, end = at)

    private fun tabletop(at: Float) = WindowFold(vertical = false, separating = true, start = at, end = at)

    private fun sides(layout: PaneLayout): SideBySide {
        assertTrue("expected side by side, got $layout", layout is SideBySide)
        return layout as SideBySide
    }

    private fun assertWidths(layout: PaneLayout, chat: Float, map: Float) {
        val sides = sides(layout)
        assertEquals(chat, sides.chatWidth, 0.5f)
        assertEquals(map, sides.mapWidth, 0.5f)
    }

    @Test
    fun `a phone is the phone app, with the bar upright and the rail on its side`() {
        assertEquals(OnePane(PaneNavigation.BAR), layout(412f, 915f))
        assertEquals(OnePane(PaneNavigation.RAIL), layout(915f, 412f))
    }

    @Test
    fun `cover screens are the phone app`() {
        assertEquals(OnePane(PaneNavigation.BAR), layout(411f, 960f))
        assertEquals(OnePane(PaneNavigation.BAR), layout(466f, 678f))
        // The wide Fold8's cover screen on its side is wide enough but too short.
        assertEquals(OnePane(PaneNavigation.RAIL), layout(700f, 440f))
    }

    @Test
    fun `an unfolded Fold8 Ultra lying flat splits on its crease`() {
        val layout = layout(752f, 835f, crease(376f))
        assertWidths(layout, 376f, 376f)
        sides(layout).let {
            assertEquals(false, it.dividerLocked)
            assertEquals(listOf(0.5f), it.anchors)
            assertEquals(false, it.listBesideConversation)
        }
    }

    @Test
    fun `half-folded like a book, the divider is locked on the fold`() {
        val layout = sides(layout(752f, 835f, book(376f)))
        assertEquals(376f, layout.chatWidth, 0.5f)
        assertEquals(376f, layout.mapWidth, 0.5f)
        assertEquals(true, layout.dividerLocked)
        assertEquals(emptyList<Float>(), layout.anchors)
    }

    @Test
    fun `a larger display size still splits on the fold`() {
        assertWidths(layout(700f, 780f, book(350f)), 350f, 350f)
    }

    @Test
    fun `the wide Fold8 held landscape gets two panes and two places to settle`() {
        val layout = sides(layout(930f, 700f, crease(465f)))
        assertEquals(465f, layout.chatWidth, 0.5f)
        assertEquals(listOf(0.5f, 2f / 3f), layout.anchors)
    }

    @Test
    fun `a fold lying flat across the screen does not stack the panes`() {
        val flatAcross = WindowFold(vertical = false, separating = false, start = 465f, end = 465f)
        assertWidths(layout(700f, 930f, flatAcross), 350f, 350f)
    }

    @Test
    fun `half-folded across the screen, the map goes above and the chat below`() {
        assertEquals(Stacked(457.5f, 457.5f, 0f), layout(412f, 915f, tabletop(457.5f)))
        assertEquals(Stacked(376f, 376f, 0f), layout(835f, 752f, tabletop(376f)))
    }

    @Test
    fun `tabletop halves too small for a side each are the phone app`() {
        assertEquals(OnePane(PaneNavigation.BAR), layout(360f, 380f, tabletop(190f)))
    }

    @Test
    fun `chat only and map only are one pane however wide`() {
        val chatOnly = LayoutChoice(arrangement = PaneArrangement.CHAT_ONLY)
        val mapOnly = LayoutChoice(arrangement = PaneArrangement.MAP_ONLY)
        assertEquals(OnePane(PaneNavigation.RAIL), layout(752f, 835f, crease(376f), chatOnly))
        assertEquals(OnePane(PaneNavigation.RAIL), layout(1280f, 800f, choice = mapOnly))
        assertEquals(OnePane(PaneNavigation.BAR), layout(412f, 915f, tabletop(457.5f), chatOnly))
    }

    @Test
    fun `a tablet on its side shows three panes`() {
        val layout = sides(layout(1280f, 800f))
        assertEquals(800f, layout.chatWidth, 0.5f)
        assertEquals(480f, layout.mapWidth, 0.5f)
        assertEquals(true, layout.listBesideConversation)
    }

    @Test
    fun `a tablet upright shows two`() {
        val layout = sides(layout(800f, 1280f))
        assertEquals(400f, layout.chatWidth, 0.5f)
        assertEquals(false, layout.listBesideConversation)
    }

    @Test
    fun `three panes start at 1200 wide`() {
        assertEquals(true, sides(layout(1200f, 800f)).listBesideConversation)
        assertEquals(false, sides(layout(1199f, 800f)).listBesideConversation)
    }

    @Test
    fun `two panes start at 600 wide and 480 tall`() {
        assertWidths(layout(600f, 480f), 320f, 280f)
        assertEquals(OnePane(PaneNavigation.BAR), layout(599f, 800f))
        assertEquals(OnePane(PaneNavigation.RAIL), layout(600f, 479f))
    }

    @Test
    fun `at exactly the minimum there is nowhere for the divider to settle but where it is`() {
        assertEquals(emptyList<Float>(), sides(layout(600f, 480f)).anchors)
    }

    @Test
    fun `large text keeps one pane until the chat side fits`() {
        val big = PaneLayouts.chatMinFor(2f)
        assertEquals(OnePane(PaneNavigation.RAIL), layout(752f, 835f, crease(376f), chatMin = big))
        assertEquals(OnePane(PaneNavigation.RAIL), layout(752f, 835f, book(376f), chatMin = big))
        assertWidths(layout(752f, 835f, crease(376f), chatMin = PaneLayouts.chatMinFor(1.3f)), 376f, 376f)
    }

    @Test
    fun `the narrowest chat side grows only beyond the allowance for larger text`() {
        assertEquals(320f, PaneLayouts.chatMinFor(1f), 0.01f)
        assertEquals(320f, PaneLayouts.chatMinFor(1.3f), 0.01f)
        assertEquals(640f, PaneLayouts.chatMinFor(2.6f), 0.01f)
    }

    @Test
    fun `the map on the start side mirrors the split`() {
        val start = LayoutChoice(mapSide = MapSide.START)
        assertWidths(layout(800f, 835f, crease(300f), start), 500f, 300f)
        assertWidths(layout(800f, 835f, crease(300f)), 320f, 480f)
        val locked = sides(layout(1114f, 720f, WindowFold(true, true, 540f, 574f), start))
        assertEquals(540f, locked.chatWidth, 0.5f)
        assertEquals(MapSide.START, locked.mapSide)
    }

    @Test
    fun `a physical gap between two screens holds nothing`() {
        val layout = sides(layout(1114f, 720f, WindowFold(vertical = true, separating = true, start = 540f, end = 574f)))
        assertEquals(540f, layout.chatWidth, 0.5f)
        assertEquals(540f, layout.mapWidth, 0.5f)
        assertEquals(34f, layout.gap, 0.5f)
    }

    @Test
    fun `where the user left the divider is kept apart for upright and wide windows`() {
        val choice = LayoutChoice(uprightShare = 0.6f, wideShare = 0.4f)
        assertWidths(layout(752f, 835f, crease(376f), choice), 451.2f, 300.8f)
        assertWidths(layout(930f, 700f, crease(465f), choice), 372f, 558f)
    }

    @Test
    fun `a remembered share never leaves either side below its minimum`() {
        assertWidths(layout(752f, 835f, choice = LayoutChoice(uprightShare = 0.95f)), 472f, 280f)
        assertWidths(layout(752f, 835f, choice = LayoutChoice(uprightShare = 0.05f)), 320f, 432f)
    }

    @Test
    fun `a fold that separates is not a reason to ignore the minimums`() {
        assertEquals(OnePane(PaneNavigation.RAIL), layout(620f, 835f, book(300f)))
    }

    @Test
    fun `a released divider settles on the nearest anchor`() {
        val window = WindowShape(930f, 700f, crease(465f))
        val layout = sides(PaneLayouts.layoutFor(window, chatAndMap))
        assertEquals(DividerSettle.Share(0.5f), PaneLayouts.settle(500f, window, layout))
        assertEquals(DividerSettle.Share(2f / 3f), PaneLayouts.settle(600f, window, layout))
    }

    @Test
    fun `dragged well below its minimum a pane closes, just below it only settles`() {
        val window = WindowShape(930f, 700f, crease(465f))
        val layout = sides(PaneLayouts.layoutFor(window, chatAndMap))
        assertEquals(DividerSettle.CloseChat, PaneLayouts.settle(200f, window, layout))
        assertEquals(DividerSettle.CloseMap, PaneLayouts.settle(930f - 150f, window, layout))
        assertEquals(DividerSettle.Share(0.5f), PaneLayouts.settle(300f, window, layout))
    }

    @Test
    fun `without anchors a released divider stays where it was let go, within the minimums`() {
        val window = WindowShape(600f, 480f)
        val layout = sides(PaneLayouts.layoutFor(window, chatAndMap))
        assertEquals(DividerSettle.Share(320f / 600f), PaneLayouts.settle(330f, window, layout))
    }
}
