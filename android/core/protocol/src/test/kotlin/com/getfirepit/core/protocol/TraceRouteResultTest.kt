package com.getfirepit.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceRouteResultTest {

    @Test
    fun `an empty route means the target answered directly`() {
        val result = TraceRouteResult.from(
            target = 42,
            route = emptyList(),
            snrTowards = emptyList(),
            routeBack = emptyList(),
            snrBack = emptyList(),
        )

        assertTrue(result.isDirect)
        assertEquals("the target itself is the only hop", 1, result.hopsOut)
    }

    @Test
    fun `snr is decoded from quarter decibels`() {
        val result = TraceRouteResult.from(
            target = 42,
            route = listOf(7),
            snrTowards = listOf(26),
            routeBack = emptyList(),
            snrBack = emptyList(),
        )

        assertEquals(6.5f, result.towards.single().snr!!, 0.001f)
    }

    @Test
    fun `a relayed route counts the relay and the target`() {
        val result = TraceRouteResult.from(
            target = 42,
            route = listOf(7, 9),
            snrTowards = listOf(20, 12),
            routeBack = emptyList(),
            snrBack = emptyList(),
        )

        assertFalse(result.isDirect)
        assertEquals(3, result.hopsOut)
    }

    @Test
    fun `a missing snr is absent rather than zero`() {
        // Zero decibels is a real reading; the absence of one is not.
        val result = TraceRouteResult.from(
            target = 42,
            route = listOf(7, 9),
            snrTowards = listOf(20),
            routeBack = emptyList(),
            snrBack = emptyList(),
        )

        assertEquals(5.0f, result.towards[0].snr!!, 0.001f)
        assertNull(result.towards[1].snr)
    }

    @Test
    fun `the firmware's unknown marker is not shown as a reading`() {
        val result = TraceRouteResult.from(
            target = 42,
            route = listOf(7),
            snrTowards = listOf(-128),
            routeBack = emptyList(),
            snrBack = emptyList(),
        )

        assertNull(result.towards.single().snr)
    }

    @Test
    fun `extra snr values without a matching hop are ignored`() {
        val result = TraceRouteResult.from(
            target = 42,
            route = listOf(7),
            snrTowards = listOf(20, 30, 40),
            routeBack = emptyList(),
            snrBack = emptyList(),
        )

        assertEquals(1, result.towards.size)
    }

    @Test
    fun `the return path is decoded independently of the outward one`() {
        val result = TraceRouteResult.from(
            target = 42,
            route = listOf(7),
            snrTowards = listOf(20),
            routeBack = listOf(9, 11),
            snrBack = listOf(8, 4),
        )

        assertEquals(listOf(9, 11), result.back.map { it.nodeNum })
        assertEquals(2.0f, result.back[0].snr!!, 0.001f)
        assertEquals(1.0f, result.back[1].snr!!, 0.001f)
    }
}
