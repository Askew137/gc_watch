package com.askew137.gcwatch.mobile.maps

import java.io.File
import java.nio.ByteBuffer

/**
 * The area a Mapsforge `.map` file covers, read from its header (big-endian):
 * 20 bytes magic `mapsforge binary OSM`, header size (int), version (int), file size (long),
 * creation date (long), then the bounding box as four ints in microdegrees: minLat, minLon, maxLat, maxLon.
 */
object MapBounds {
    data class Box(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double)

    private const val MAGIC = "mapsforge binary OSM"
    private const val BOX_OFFSET = 44
    private const val HEADER_BYTES = BOX_OFFSET + 16

    fun read(file: File): Box? = try {
        file.inputStream().use { input ->
            val buf = ByteArray(HEADER_BYTES)
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            if (n == buf.size) parse(buf) else null
        }
    } catch (e: Exception) {
        null
    }

    fun parse(header: ByteArray): Box? {
        if (header.size < HEADER_BYTES || String(header, 0, MAGIC.length, Charsets.US_ASCII) != MAGIC) return null
        val b = ByteBuffer.wrap(header, BOX_OFFSET, 16) // big-endian by default
        val box = Box(b.int / 1e6, b.int / 1e6, b.int / 1e6, b.int / 1e6)
        val sane = box.minLat in -90.0..90.0 && box.maxLat in -90.0..90.0 &&
            box.minLon in -180.0..180.0 && box.maxLon in -180.0..180.0 &&
            box.minLat <= box.maxLat && box.minLon <= box.maxLon
        return box.takeIf { sane }
    }
}
