package com.getfirepit.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedRadiosTest {

    private fun radio(id: String, name: String, role: NodeRole) = SavedRadio(id, name, role)

    @Test
    fun `assigning personal takes it from whoever held it`() {
        val before = listOf(radio("a", "Pocket", NodeRole.PERSONAL))
        val after = SavedRadios.assign(before, radio("b", "Spare", NodeRole.PERSONAL))

        assertEquals("b", SavedRadios.personal(after)?.identifier)
        assertEquals(NodeRole.BASE, after.first { it.identifier == "a" }.role)
    }

    @Test
    fun `a demoted personal is kept, not forgotten`() {
        val before = listOf(radio("a", "Pocket", NodeRole.PERSONAL))
        val after = SavedRadios.assign(before, radio("b", "Spare", NodeRole.PERSONAL))

        assertEquals(2, after.size)
    }

    @Test
    fun `any number of bases and routers`() {
        var radios = emptyList<SavedRadio>()
        radios = SavedRadios.assign(radios, radio("a", "Camp", NodeRole.BASE))
        radios = SavedRadios.assign(radios, radio("b", "Hut", NodeRole.BASE))
        radios = SavedRadios.assign(radios, radio("c", "Ridge", NodeRole.ROUTER))
        radios = SavedRadios.assign(radios, radio("d", "Peak", NodeRole.ROUTER))

        assertEquals(4, radios.size)
        assertNull(SavedRadios.personal(radios))
    }

    @Test
    fun `re-assigning the same radio replaces it rather than duplicating`() {
        var radios = SavedRadios.assign(emptyList(), radio("a", "Camp", NodeRole.BASE))
        radios = SavedRadios.assign(radios, radio("a", "Camp", NodeRole.ROUTER))

        assertEquals(1, radios.size)
        assertEquals(NodeRole.ROUTER, radios.single().role)
    }

    @Test
    fun `yours sorts first, the rest by name`() {
        var radios = SavedRadios.assign(emptyList(), radio("c", "Zulu", NodeRole.BASE))
        radios = SavedRadios.assign(radios, radio("a", "alpha", NodeRole.BASE))
        radios = SavedRadios.assign(radios, radio("b", "Pocket", NodeRole.PERSONAL))

        assertEquals(listOf("Pocket", "alpha", "Zulu"), radios.map { it.name })
    }

    @Test
    fun `forgetting removes only that radio`() {
        var radios = SavedRadios.assign(emptyList(), radio("a", "Camp", NodeRole.BASE))
        radios = SavedRadios.assign(radios, radio("b", "Hut", NodeRole.BASE))

        assertEquals(listOf("b"), SavedRadios.forget(radios, "a").map { it.identifier })
    }

    @Test
    fun `demoting the only personal leaves nobody holding it`() {
        val before = listOf(radio("a", "Pocket", NodeRole.PERSONAL))
        val after = SavedRadios.assign(before, radio("a", "Pocket", NodeRole.BASE))

        assertNull(SavedRadios.personal(after))
    }
}
