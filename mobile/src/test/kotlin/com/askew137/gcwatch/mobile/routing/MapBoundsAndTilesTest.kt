package com.askew137.gcwatch.mobile.routing

import com.askew137.gcwatch.mobile.maps.MapBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class MapBoundsAndTilesTest {
    private fun header(minLat: Int, minLon: Int, maxLat: Int, maxLon: Int): ByteArray {
        val b = ByteBuffer.allocate(64)
        b.put("mapsforge binary OSM".toByteArray()); b.putInt(4521); b.putInt(3); b.putLong(3799537); b.putLong(0)
        b.putInt(minLat); b.putInt(minLon); b.putInt(maxLat); b.putInt(maxLon)
        return b.array()
    }

    @Test fun `reads the bounding box of the Usti map header`() {
        val box = MapBounds.parse(header(50_584_000, 13_915_000, 50_780_000, 14_148_000))!!
        assertEquals(50.584, box.minLat, 1e-9)
        assertEquals(14.148, box.maxLon, 1e-9)
        assertEquals(listOf("E10_N50"), TileNames.tilesCovering(box.minLat, box.minLon, box.maxLat, box.maxLon))
    }

    @Test fun `a map crossing a tile border needs both tiles`() {
        assertEquals(listOf("E10_N50", "E15_N50"), TileNames.tilesCovering(50.1, 14.0, 50.9, 16.0))
        assertEquals(listOf("E10_N45", "E15_N45", "E10_N50", "E15_N50"), TileNames.tilesCovering(49.0, 14.0, 51.0, 16.0))
    }

    @Test fun `ending exactly on a border does not add the next tile`() {
        assertEquals(listOf("E10_N50"), TileNames.tilesCovering(50.0, 10.0, 55.0, 15.0))
    }

    @Test fun `southern and western hemispheres`() {
        assertEquals(listOf("W5_N50"), TileNames.tilesCovering(51.3, -0.5, 51.7, 0.0))
        assertEquals(listOf("W5_N50", "E0_N50"), TileNames.tilesCovering(51.3, -0.5, 51.7, 0.3))
        assertEquals(listOf("E150_S35"), TileNames.tilesCovering(-33.9, 151.0, -33.7, 151.3))
    }

    @Test fun `rejects headers that are not a mapsforge map`() {
        assertNull(MapBounds.parse(ByteArray(64)))
        assertNull(MapBounds.parse(header(60_000_000, 0, 50_000_000, 0))) // min > max
        assertNull(MapBounds.parse("short".toByteArray()))
    }
}
