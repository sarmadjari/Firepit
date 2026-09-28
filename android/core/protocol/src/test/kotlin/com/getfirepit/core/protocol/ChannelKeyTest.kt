package com.getfirepit.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelKeyTest {
    @Test
    fun `no key at all is not encrypted`() {
        assertEquals(ChannelKey.NONE, ChannelKey.of(null))
        assertEquals(ChannelKey.NONE, ChannelKey.of(byteArrayOf()))
        assertEquals(ChannelKey.NONE, ChannelKey.of(byteArrayOf(0)))
    }

    @Test
    fun `the one byte shorthand is the key every radio ships with`() {
        assertEquals(ChannelKey.DEFAULT, ChannelKey.of(byteArrayOf(1)))
        assertEquals(ChannelKey.DEFAULT, ChannelKey.of(byteArrayOf(10)))
    }

    @Test
    fun `a full length key is private`() {
        assertEquals(ChannelKey.PRIVATE, ChannelKey.of(ByteArray(16) { 7 }))
        assertEquals(ChannelKey.PRIVATE, ChannelKey.of(ByteArray(32) { 7 }))
    }

    @Test
    fun `an unrecognised key is called public rather than guessed private`() {
        assertEquals(ChannelKey.DEFAULT, ChannelKey.of(ByteArray(8) { 7 }))
    }

    /**
     * The default key is sixteen bytes, exactly like a real AES-128 key, so a
     * check that only measured length would call the most public key in the
     * protocol private.
     */
    @Test
    fun `the well-known key written out in full is still the default`() {
        assertEquals(
            ChannelKey.DEFAULT,
            ChannelKey.of(MeshtasticChannel.DEFAULT_KEY.toByteArray()),
        )
        assertFalse(ChannelKey.of(MeshtasticChannel.DEFAULT_KEY.toByteArray()).isPrivate)
    }

    @Test
    fun `only a full key counts as private`() {
        assertTrue(ChannelKey.of(ByteArray(32) { 7 }).isPrivate)
        assertFalse(ChannelKey.of(byteArrayOf(1)).isPrivate)
        assertFalse(ChannelKey.of(null).isPrivate)
    }
}
