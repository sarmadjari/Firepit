package com.getfirepit.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonTest {
    @Test
    fun `keeps the name and tag it is given`() {
        val person = Person.of(id = 7, name = "Sarmad", tag = "SRM")

        assertEquals("Sarmad", person.name)
        assertEquals("SRM", person.tag)
    }

    @Test
    fun `suggests initials when no tag is offered`() {
        assertEquals("SJ", Person.of(id = 1, name = "Sarmad Jamal", tag = "").tag)
    }

    @Test
    fun `one name gives its opening two letters`() {
        assertEquals("SA", Person.initialsFor("Sarmad"))
    }

    @Test
    fun `a surname replaces the second letter`() {
        assertEquals("SJ", Person.initialsFor("Sarmad Jamal"))
    }

    @Test
    fun `middle names are skipped in favour of the surname`() {
        assertEquals("AC", Person.initialsFor("Anna Bea Carter"))
    }

    @Test
    fun `a single letter stands alone rather than being padded`() {
        assertEquals("S", Person.initialsFor("S"))
    }

    @Test
    fun `no name gives no initials`() {
        assertEquals("", Person.initialsFor("   "))
    }

    @Test
    fun `initials follow alphabets that have no capitals`() {
        assertEquals("سج", Person.initialsFor("سرمد جمال"))
    }

    @Test
    fun `a name too long for a control packet is cut to fit`() {
        val person = Person.of(id = 1, name = "x".repeat(80), tag = "X")

        assertEquals(OwnerName.MAX_LONG_BYTES, person.name.toByteArray().size)
    }

    @Test
    fun `a tag longer than four characters is cut to fit`() {
        assertEquals("SARM", Person.of(id = 1, name = "Sarmad", tag = "SARMAD").tag)
    }

    @Test
    fun `counts bytes rather than characters so other alphabets survive`() {
        val person = Person.of(id = 1, name = "سرمد", tag = "سر")

        assertEquals("سرمد", person.name)
        assertEquals("سر", person.tag)
    }

    @Test
    fun `the identity is not derived from any node, so it survives changing radio`() {
        val onPocketRadio = Person.of(id = 4242, name = "Sarmad", tag = "SRM", colourSlot = 3)
        val onBaseStation = Person.of(id = 4242, name = "Sarmad", tag = "SRM", colourSlot = 3)

        assertEquals(onPocketRadio, onBaseStation)
    }

    @Test
    fun `no colour chosen stays no colour chosen`() {
        assertNull(Person.of(id = 1, name = "Sarmad", tag = "SRM").colourSlot)
    }
}
