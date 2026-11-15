package com.askew137.gcwatch.core.communication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapTransferProtocolTest {
    @Test
    fun `channel path round-trips names with spaces and diacritics`() {
        val path = MapTransferProtocol.channelPath("Ústí nad Labem 2026.map", offset = 1024, size = 4096)
        val t = MapTransferProtocol.parseChannelPath(path)!!
        assertEquals("Ústí nad Labem 2026.map", t.name)
        assertEquals(1024L, t.offset)
        assertEquals(4096L, t.size)
    }

    @Test
    fun `rejects paths and names that could escape the maps directory`() {
        assertNull(MapTransferProtocol.parseChannelPath("/map_transfer2/0/10/..%2Fevil.map"))
        assertNull(MapTransferProtocol.parseChannelPath("/map_transfer2/0/10/a%2Fb.map"))
        assertNull(MapTransferProtocol.parseChannelPath("/map_transfer2/0/10/evil.apk"))
        assertNull(MapTransferProtocol.parseChannelPath("/map_transfer/evil.map")) // old style
    }

    @Test
    fun `rejects a nonsensical offset or size`() {
        assertNull(MapTransferProtocol.parseChannelPath("/map_transfer2/10/10/a.map")) // nothing left to send
        assertNull(MapTransferProtocol.parseChannelPath("/map_transfer2/0/0/a.map"))
        assertNull(MapTransferProtocol.parseChannelPath("/map_transfer2/-1/10/a.map"))
        assertNull(MapTransferProtocol.parseChannelPath("/map_transfer2/x/10/a.map"))
    }

    @Test
    fun `messages round-trip`() {
        val d = MapTransferProtocol.decode(MapTransferProtocol.encode("a.map", 123, "ok"))!!
        assertEquals("a.map", d.first)
        assertEquals(123L, d.second)
        assertEquals("ok", d.third)
        assertTrue(MapTransferProtocol.decode("garbage".toByteArray()) == null)
        assertEquals(MapTransferProtocol.NO_SPACE, MapTransferProtocol.decode(MapTransferProtocol.encode("a.map", MapTransferProtocol.NO_SPACE))!!.second)
    }
}
