package com.askew137.gcwatch.wear.map

import android.content.Context
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.map.reader.MapFile
import timber.log.Timber
import java.io.File

/**
 * Scans [Context.filesDir]/maps/ for *.map files and returns those whose bounding box
 * contains the given position, or all files when [automatic] is false.
 */
class AutoMapSelector(private val context: Context) {

    private val mapsDir: File get() = File(context.filesDir, "maps")

    // Reading the bounding box means opening the .map file. That is called on the UI thread on every
    // GPS fix and now on every finger drag, so it is done once per file (and again if the file changes).
    private data class CachedBox(val length: Long, val modified: Long, val box: BoundingBox?)
    private val boxes = java.util.concurrent.ConcurrentHashMap<String, CachedBox>()

    private fun boundingBoxOf(file: File): BoundingBox? {
        val length = file.length()
        val modified = file.lastModified()
        boxes[file.path]?.let { if (it.length == length && it.modified == modified) return it.box }
        val box = try {
            val mapFile = MapFile(file)
            try { mapFile.mapFileInfo.boundingBox } finally { mapFile.close() }
        } catch (e: Exception) {
            Timber.e(e, "AutoMapSelector: failed to read bounding box from %s", file.name)
            null
        }
        boxes[file.path] = CachedBox(length, modified, box)
        return box
    }

    /** All .map files found in the maps directory. */
    fun availableMapFiles(): List<File> {
        if (!mapsDir.exists()) return emptyList()
        return mapsDir.listFiles { f -> f.extension == "map" }?.toList() ?: emptyList()
    }

    /**
     * Returns the subset of [availableMapFiles] whose Mapsforge bounding box contains
     * [lat]/[lon]. If [automatic] is false, returns all available files unchanged.
     */
    fun selectMapsForPosition(lat: Double, lon: Double, automatic: Boolean): List<File> {
        val available = availableMapFiles()
        if (!automatic) return available

        return available.filter { file -> boundingBoxOf(file)?.contains(lat, lon) == true }
    }

    /**
     * Centre of the first available map's bounding box: a sensible place to show before the
     * first GPS fix (otherwise the map would sit on the default location, usually off the map).
     */
    fun firstMapCenter(): Pair<Double, Double>? {
        for (file in availableMapFiles().sortedBy { it.name }) {
            val center = boundingBoxOf(file)?.centerPoint ?: continue
            return center.latitude to center.longitude
        }
        return null
    }
}
