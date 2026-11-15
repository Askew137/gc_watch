package com.askew137.gcwatch.mobile.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TileNamesTest {
    @Test fun `known places map to their tiles`() {
        assertEquals("E10_N50", TileNames.tileFor(50.66, 14.03)) // Ústí nad Labem
        assertEquals("E15_N45", TileNames.tileFor(48.2, 16.4))   // Vienna
        assertEquals("E10_N45", TileNames.tileFor(48.14, 11.58)) // Munich
        assertEquals("W5_N50", TileNames.tileFor(51.5, -0.12))   // London
        assertEquals("W75_N40", TileNames.tileFor(40.7, -74.0))  // New York
        assertEquals("E150_S35", TileNames.tileFor(-33.87, 151.2)) // Sydney
        assertEquals("E10_N50", TileNames.tileFor(50.0, 10.0))   // exactly on the corner
    }

    @Test fun `input accepts tile names and coordinates`() {
        assertEquals("E15_N45", TileNames.resolve("e15_n45"))
        assertEquals("E15_N45", TileNames.resolve("48.2, 16.4"))
        assertEquals("E15_N45", TileNames.resolve("48,2; 16,4"))
        assertEquals("E15_N45", TileNames.resolve("48.2 16.4"))
        assertEquals("W5_N50", TileNames.resolve("51.5, -0.12"))
    }

    @Test fun `rejects nonsense`() {
        assertNull(TileNames.resolve("E12_N50"))   // not a multiple of 5
        assertNull(TileNames.resolve("E10_N95"))
        assertNull(TileNames.resolve("../etc"))
        assertNull(TileNames.resolve("91, 10"))
        assertNull(TileNames.resolve(""))
    }
}
