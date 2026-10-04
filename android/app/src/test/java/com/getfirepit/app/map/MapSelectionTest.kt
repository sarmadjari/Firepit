package com.getfirepit.app.map

import com.getfirepit.core.model.ChannelRole
import com.getfirepit.core.model.MapPin
import com.getfirepit.core.model.MeshNode
import com.getfirepit.core.model.RoomChannel
import com.getfirepit.core.model.RoomKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The map beside a conversation follows it (UX §6.11.6). */
class MapSelectionTest {

    private val me = 1
    private val maya = 2
    private val ryan = 3
    private val stranger = 9
    private val onMap = listOf(me, maya, ryan, stranger).map { MeshNode(nodeNum = it) }
    private val camp = Following.Room(roomId = 100, name = "Camp")
    private val withMaya = Following.Direct(nodeNum = maya, name = "Maya")

    private fun nums(nodes: List<MeshNode>) = nodes.map { it.nodeNum }

    private fun pin(id: Int, roomId: Int) =
        MapPin(id = id, channel = 1, latitudeI = 0, longitudeI = 0, name = "p$id", createdBy = maya, receivedAt = 0, roomId = roomId)

    private fun room(id: Int, index: Int) =
        RoomChannel(index = index, name = "r$id", role = ChannelRole.SECONDARY, id = id, positionPrecision = 32, kind = RoomKind.FIREPIT)

    @Test
    fun `following a room shows its members and you, and nobody else`() {
        val shown = MapSelection.nodes(onMap, Followed(camp, setOf(maya)), MapFilter.ALL, ours = setOf(maya, ryan), myNodeNum = me)

        assertEquals(listOf(me, maya), nums(shown))
    }

    @Test
    fun `following a direct chat shows that person and you`() {
        val shown = MapSelection.nodes(onMap, Followed(withMaya, setOf(maya)), MapFilter.OURS, ours = emptySet(), myNodeNum = me)

        assertEquals(listOf(me, maya), nums(shown))
    }

    @Test
    fun `with nothing followed the chosen filter decides`() {
        assertEquals(nums(onMap), nums(MapSelection.nodes(onMap, null, MapFilter.ALL, setOf(maya), me)))
        assertEquals(listOf(maya), nums(MapSelection.nodes(onMap, null, MapFilter.OURS, setOf(maya), me)))
    }

    @Test
    fun `a followed room keeps its own pins, and a direct chat none`() {
        val pins = listOf(pin(1, roomId = 100), pin(2, roomId = 200))

        assertEquals(listOf(1), MapSelection.pins(pins, camp).map { it.id })
        assertEquals(emptyList<Int>(), MapSelection.pins(pins, withMaya).map { it.id })
        assertEquals(listOf(1, 2), MapSelection.pins(pins, null).map { it.id })
    }

    @Test
    fun `stopping holds only for the conversation it was stopped for`() {
        assertNull(MapSelection.followed(open = camp, stopped = camp))
        assertEquals(withMaya, MapSelection.followed(open = withMaya, stopped = camp))
        assertEquals(camp, MapSelection.followed(open = camp, stopped = null))
        assertNull(MapSelection.followed(open = null, stopped = null))
    }

    @Test
    fun `a pin goes to the open room first, then the shared one, then the only one`() {
        val a = room(100, index = 1)
        val b = room(200, index = 2)

        assertEquals(a, MapSelection.pinRoom(listOf(a, b), open = camp, sharingRoomId = 200))
        assertEquals(b, MapSelection.pinRoom(listOf(a, b), open = withMaya, sharingRoomId = 200))
        assertEquals(b, MapSelection.pinRoom(listOf(a, b), open = null, sharingRoomId = 200))
        assertEquals(a, MapSelection.pinRoom(listOf(a), open = null, sharingRoomId = null))
        assertNull(MapSelection.pinRoom(listOf(a, b), open = null, sharingRoomId = null))
    }

    @Test
    fun `an open room you cannot share in is passed over, not guessed at`() {
        val b = room(200, index = 2)

        assertEquals(b, MapSelection.pinRoom(listOf(b), open = camp, sharingRoomId = null))
    }
}
