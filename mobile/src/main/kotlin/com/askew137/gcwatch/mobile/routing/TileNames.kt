package com.askew137.gcwatch.mobile.routing

import kotlin.math.floor

/**
 * BRouter routing tiles are 5x5 degree squares named by their south-west corner, e.g. `E10_N50` covers
 * 10–15°E, 50–55°N; `W5_S10` covers 5°W–0°, 10°S–5°S (brouter.de/brouter/segments4/<name>.rd5).
 */
object TileNames {
    private val tileRegex = Regex("""^([EW])(\d{1,3})_([NS])(\d{1,2})$""", RegexOption.IGNORE_CASE)
    private val coordRegex = Regex("""^\s*(-?\d{1,2}(?:[.,]\d+)?)\s*[,;\s]\s*(-?\d{1,3}(?:[.,]\d+)?)\s*$""")

    fun tileFor(lat: Double, lon: Double): String? {
        if (lat !in -90.0..89.999 || lon !in -180.0..179.999) return null
        val la = floor(lat / 5).toInt() * 5
        val lo = floor(lon / 5).toInt() * 5
        return "${if (lo >= 0) "E" else "W"}${kotlin.math.abs(lo)}_${if (la >= 0) "N" else "S"}${kotlin.math.abs(la)}"
    }

    /** A valid tile name (normalised to upper case), or null. Corners must be multiples of 5 within range. */
    fun parseName(input: String): String? {
        val m = tileRegex.matchEntire(input.trim()) ?: return null
        val lon = m.groupValues[2].toInt()
        val lat = m.groupValues[4].toInt()
        if (lon % 5 != 0 || lat % 5 != 0 || lon > 180 || lat > 90) return null
        return "${m.groupValues[1].uppercase()}${lon}_${m.groupValues[3].uppercase()}$lat"
    }

    /** Accepts a tile name (`E15_N45`) or coordinates as `lat, lon` (`48.2, 16.4`, comma decimals allowed with `;`). */
    fun resolve(input: String): String? {
        parseName(input)?.let { return it }
        val m = coordRegex.matchEntire(input.replace("°", "")) ?: return null
        val lat = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null
        return tileFor(lat, lon)
    }
}
