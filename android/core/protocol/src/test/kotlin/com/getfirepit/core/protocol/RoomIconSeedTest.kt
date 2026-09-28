package com.getfirepit.core.protocol

import com.getfirepit.core.model.ChannelRole
import com.getfirepit.core.model.RoomChannel
import com.getfirepit.core.model.RoomKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Everyone in a room must see the same icon.
 *
 * The icon is picked from a seed, and the two kinds of room agree on different
 * things: a Firepit room shares its id through the invite, while a Meshtastic
 * channel may carry any id at all — or none — depending on which app created
 * it. Its name is the only thing every radio on it is guaranteed to match.
 */
class RoomIconSeedTest {

    private fun room(
        name: String,
        id: Int,
        kind: RoomKind,
        index: Int = 1,
    ) = RoomChannel(
        index = index,
        name = name,
        role = ChannelRole.SECONDARY,
        id = id,
        positionPrecision = 0,
        kind = kind,
    )

    /** The case that prompted this: one phone made the channel, the other typed it in. */
    @Test
    fun `a meshtastic channel looks the same however each phone learned of it`() {
        val madeInMeshtasticApp = room("SJ", id = 0x5A5A5A5A, RoomKind.MESHTASTIC_PRIVATE)
        val addedByFirepit = room("SJ", id = 0, RoomKind.MESHTASTIC_PRIVATE)
        val typedInByHand = room("SJ", id = 0x0BADF00D, RoomKind.MESHTASTIC_PRIVATE)

        assertEquals(madeInMeshtasticApp.iconSeed, addedByFirepit.iconSeed)
        assertEquals(madeInMeshtasticApp.iconSeed, typedInByHand.iconSeed)
    }

    @Test
    fun `a public channel behaves the same way`() {
        assertEquals(
            room("LongFast", id = 0, RoomKind.MESHTASTIC_PUBLIC).iconSeed,
            room("LongFast", id = 99, RoomKind.MESHTASTIC_PUBLIC).iconSeed,
        )
    }

    /** A Firepit room carries its id in the invite, so that is what every member has. */
    @Test
    fun `a firepit room follows its shared id`() {
        val mine = room("camp", id = 0x0BADF00D, RoomKind.FIREPIT)
        val theirs = room("camp", id = 0x0BADF00D, RoomKind.FIREPIT, index = 4)

        assertEquals(mine.iconSeed, theirs.iconSeed)
        assertEquals(0x0BADF00D, mine.iconSeed)
    }

    /** Slot numbers move when somebody leaves a room; the icon must not move with them. */
    @Test
    fun `the slot a room sits in does not change its icon`() {
        (1..7).forEach { slot ->
            assertEquals(
                room("SJ", id = 0, RoomKind.MESHTASTIC_PRIVATE, index = 1).iconSeed,
                room("SJ", id = 0, RoomKind.MESHTASTIC_PRIVATE, index = slot).iconSeed,
            )
        }
    }

    @Test
    fun `different channels mostly get different icons`() {
        val names = listOf("SJ", "camp", "LongFast", "trail", "base", "north", "crew", "hut")
        val icons = names.map { name ->
            seedToIcon(room(name, id = 0, RoomKind.MESHTASTIC_PRIVATE).iconSeed)
        }

        // Eight icons and eight names will not spread perfectly, but a hash that
        // put everything in one bucket would make the icon useless.
        assertTrue("icons collapsed to ${icons.distinct()}", icons.distinct().size >= 4)
    }

    /** A Firepit room that somehow lost its id still gets a stable icon, not a crash. */
    @Test
    fun `a room with no id falls back to its name`() {
        val nameless = room("camp", id = 0, RoomKind.FIREPIT)

        assertEquals(room("camp", id = 0, RoomKind.MESHTASTIC_PRIVATE).iconSeed, nameless.iconSeed)
    }

    /**
     * The seed is hashed the same way on every platform, so this pins the
     * values iOS has to reproduce.
     */
    @Test
    fun `the name hash is fixed, so other platforms can match it`() {
        // FNV-1a 32-bit: offset basis for the empty string, then "SJ".
        assertEquals(-0x7ee3623b, room("", id = 0, RoomKind.MESHTASTIC_PRIVATE).iconSeed)
        assertEquals(1560448184, room("SJ", id = 0, RoomKind.MESHTASTIC_PRIVATE).iconSeed)
    }

    private fun seedToIcon(seed: Int) = ((seed % 8) + 8) % 8
}
