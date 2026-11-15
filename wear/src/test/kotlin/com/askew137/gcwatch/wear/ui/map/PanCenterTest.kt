package com.askew137.gcwatch.wear.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanCenterTest {
    private val start = LatLon(50.0, 14.0)
    private val zoom = 14

    @Test
    fun `dragging the map to the right moves the centre west by a tile per 256 px`() {
        val r = panCenter(start, dxPx = 256f, dyPx = 0f, zoom = zoom, rotationDeg = 0f)
        assertEquals(14.0 - 360.0 / (1 shl zoom), r.lon, 1e-6)
        assertEquals(50.0, r.lat, 1e-6)
    }

    @Test
    fun `dragging the map down moves the centre north`() {
        val r = panCenter(start, dxPx = 0f, dyPx = 100f, zoom = zoom, rotationDeg = 0f)
        assertTrue(r.lat > 50.0)
        assertEquals(14.0, r.lon, 1e-6)
    }

    @Test
    fun `with the map turned 90 degrees clockwise a drag to the right moves the centre south`() {
        // North points to the screen's right, so content dragged right = content moved north = centre moves south.
        val r = panCenter(start, dxPx = 100f, dyPx = 0f, zoom = zoom, rotationDeg = 90f)
        assertTrue(r.lat < 50.0)
        assertEquals(14.0, r.lon, 1e-6)
    }
}
