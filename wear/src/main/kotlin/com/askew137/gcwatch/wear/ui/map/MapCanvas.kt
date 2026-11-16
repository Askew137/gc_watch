package com.askew137.gcwatch.wear.ui.map

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.askew137.gcwatch.core.model.ComputedRoute
import com.askew137.gcwatch.core.model.SegmentType
import com.askew137.gcwatch.core.model.Waypoint
import com.askew137.gcwatch.wear.navigation.TargetLineMode
import com.askew137.gcwatch.wear.map.MapEngineService
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.PI

private const val TILE_SIZE_PX = 256

data class LatLon(val lat: Double, val lon: Double)

/**
 * Renders Mapsforge map tiles, an optional route overlay, and the current-position dot.
 *
 * @param engine       The [MapEngineService] used to render tiles.
 * @param center       Current WGS-84 center of the viewport.
 * @param me           Position of the wearer (dot, view cone, start of the air line); null = unknown.
 * @param zoom         Zoom level (8f..19f).
 * @param rotationDeg  Canvas rotation in degrees (0 = north-up).
 * @param headingDeg   Where the wearer is looking (0 = north, clockwise); null = no cone yet.
 * @param route        Optional computed route (drawn in [TargetLineMode.ROUTE]).
 * @param target       Active target; always marked, connected per [lineMode].
 * @param lineMode     Air line, route, or marker only.
 * @param accentColor  Accent color used for the position dot and route highlight.
 */
@Composable
fun MapCanvas(
    engine: MapEngineService,
    center: LatLon,
    /**
     * Where the wearer is; the dot, cone and air line sit there even when the view is panned away
     * from it. Null = no GPS fix yet: nothing of that is drawn (the map is then centred on the target
     * or the map, which must not look like "I am here").
     */
    me: LatLon?,
    zoom: Float,
    mapsVersion: Int,
    rotationDeg: Float,
    headingDeg: Float?,
    route: ComputedRoute?,
    target: Waypoint?,
    lineMode: TargetLineMode,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    val zoomByte = remember(zoom) { zoom.toInt().toByte() }

    Canvas(modifier = modifier.fillMaxSize()) {
        @Suppress("UNUSED_VARIABLE") val redrawOnMapsChange = mapsVersion
        val canvasW = size.width
        val canvasH = size.height

        // The wearer in the (un-rotated) map frame; null without a GPS fix.
        val meMap = me?.let {
            val (mx, my) = latLonToCanvas(it.lat, it.lon, center, zoomByte.toInt(), canvasW, canvasH)
            Offset(mx, my)
        }

        withTransform(
            transformBlock = { rotate(degrees = rotationDeg, pivot = center(size)) }
        ) {
            drawTiles(engine, center, zoomByte, canvasW, canvasH)
            if (target != null) {
                val (tx, ty) = latLonToCanvas(target.lat, target.lon, center, zoomByte.toInt(), canvasW, canvasH)
                when {
                    lineMode == TargetLineMode.ROUTE && route != null ->
                        drawRoute(route, center, zoomByte, canvasW, canvasH, accentColor)
                    lineMode != TargetLineMode.OFF && meMap != null ->
                        drawAirLine(from = meMap, to = Offset(tx, ty), accentColor = accentColor)
                }
                drawTargetMarker(Offset(tx, ty), accentColor)
            }
        }

        // View-direction cone (un-rotated): the map is rotated clockwise by rotationDeg, so a
        // heading of h points at h + rotationDeg clockwise from the top of the screen.
        // The wearer's position on screen (rotated like the map); nothing is drawn without a fix.
        val mePos = meMap?.let { rotatePoint(it, center(size), rotationDeg) }
        // Target outside the round screen: an arrow on the edge says which way it is.
        if (target != null) {
            val (tx, ty) = latLonToCanvas(target.lat, target.lon, center, zoomByte.toInt(), canvasW, canvasH)
            drawOffscreenArrow(rotatePoint(Offset(tx, ty), center(size), rotationDeg), accentColor)
        }
        if (mePos == null) return@Canvas
        if (headingDeg != null) {
            val c = mePos
            val r = 50.dp.toPx()
            val half = 24f
            drawArc(
                color = accentColor.copy(alpha = 0.32f),
                startAngle = headingDeg + rotationDeg - 90f - half,
                sweepAngle = 2 * half,
                useCenter = true,
                topLeft = Offset(c.x - r, c.y - r),
                size = Size(2 * r, 2 * r),
            )
        }

        // Always draw position dot on top (un-rotated), with a dark halo so it
        // stays visible on any map colour / accent colour.
        drawCircle(
            color = Color.Black.copy(alpha = 0.85f),
            radius = 11.dp.toPx(),
            center = mePos,
        )
        drawCircle(
            color = accentColor,
            radius = 8.dp.toPx(),
            center = mePos,
        )
        drawCircle(
            color = Color.White,
            radius = 8.dp.toPx(),
            center = mePos,
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

// ── Tile rendering ────────────────────────────────────────────────────────────

private fun DrawScope.drawTiles(
    engine: MapEngineService,
    center: LatLon,
    zoom: Byte,
    canvasW: Float,
    canvasH: Float,
) {
    val scale = TILE_SIZE_PX.toFloat()
    val (centerTileX, centerTileY, offX, offY) = worldToTile(center.lat, center.lon, zoom.toInt())

    val tilesX = (canvasW / scale + 2).toInt()
    val tilesY = (canvasH / scale + 2).toInt()
    val startX = centerTileX - tilesX / 2
    val startY = centerTileY - tilesY / 2

    // Nearest to the centre first, so the tile under the wearer appears before the edges.
    val coords = (startX..startX + tilesX).flatMap { tx -> (startY..startY + tilesY).map { ty -> tx to ty } }
        .sortedBy { (tx, ty) ->
            val dx = (tx - centerTileX).toFloat()
            val dy = (ty - centerTileY).toFloat()
            dx * dx + dy * dy
        }
    val images = engine.tilesFor(coords.map { (tx, ty) -> Triple(tx, ty, zoom.toInt()) })
    coords.forEachIndexed { i, (tx, ty) ->
        val image = images[i] ?: return@forEachIndexed
        val px = (tx - centerTileX) * scale + canvasW / 2 - offX
        val py = (ty - centerTileY) * scale + canvasH / 2 - offY
        drawImage(
            image = image,
            topLeft = Offset(kotlin.math.round(px), kotlin.math.round(py)),
        )
    }
}

// ── Target overlays ───────────────────────────────────────────────────────────

/** Widened copy of a stroke style, used as a dark outline so lines read on any map colour. */
private fun Stroke.casing(extraPx: Float) = Stroke(
    width = width + 2 * extraPx,
    cap = cap,
    join = join,
    pathEffect = pathEffect,
)

private fun DrawScope.drawAirLine(from: Offset, to: Offset, accentColor: Color) {
    val path = Path().apply {
        moveTo(from.x, from.y)
        lineTo(to.x, to.y)
    }
    val style = Stroke(
        width = 3.dp.toPx(),
        cap = StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx()), 0f),
    )
    drawPath(path = path, color = Color.Black.copy(alpha = 0.55f), style = style.casing(2.dp.toPx()))
    drawPath(path = path, color = accentColor, style = style)
}

/** Hollow ring with a dark halo: clearly different from the filled "me" dot. */
private fun DrawScope.drawTargetMarker(at: Offset, accentColor: Color) {
    drawCircle(color = Color.Black.copy(alpha = 0.85f), radius = 12.dp.toPx(), center = at)
    drawCircle(color = Color.White, radius = 9.dp.toPx(), center = at, style = Stroke(width = 3.dp.toPx()))
    drawCircle(color = accentColor, radius = 4.dp.toPx(), center = at)
}

// ── Route overlay ─────────────────────────────────────────────────────────────

private fun DrawScope.drawRoute(
    route: ComputedRoute,
    center: LatLon,
    zoom: Byte,
    canvasW: Float,
    canvasH: Float,
    accentColor: Color,
) {
    route.segments.forEach { segment ->
        val path = Path()
        segment.points.forEachIndexed { i, pt ->
            val (sx, sy) = latLonToCanvas(pt.lat, pt.lon, center, zoom.toInt(), canvasW, canvasH)
            if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
        }

        val strokeWidth = 4.dp.toPx()
        val style = when (segment.type) {
            SegmentType.ROAD -> Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
            SegmentType.DASHED_DIRECT -> Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(
                    intervals = floatArrayOf(12.dp.toPx(), 6.dp.toPx()),
                    phase = 0f,
                ),
            )
        }
        drawPath(path = path, color = Color.Black.copy(alpha = 0.55f), style = style.casing(2.dp.toPx()))
        drawPath(path = path, color = accentColor, style = style)
    }
}

// ── Coordinate math ──────────────────────────────────────────────────────────

private data class TileCoord(
    val tileX: Int,
    val tileY: Int,
    val offX: Float, // pixel offset within tile
    val offY: Float,
)

private fun worldToTile(lat: Double, lon: Double, zoom: Int): TileCoord {
    val n = 2.0.pow(zoom.toDouble())
    val xTile = ((lon + 180.0) / 360.0 * n)
    val yTile = ((1.0 - ln(tan(lat * PI / 180.0) + 1.0 / cos(lat * PI / 180.0)) / PI) / 2.0 * n)
    val offX = ((xTile - xTile.toInt()) * TILE_SIZE_PX).toFloat()
    val offY = ((yTile - yTile.toInt()) * TILE_SIZE_PX).toFloat()
    return TileCoord(xTile.toInt(), yTile.toInt(), offX, offY)
}

private fun latLonToCanvas(
    lat: Double,
    lon: Double,
    center: LatLon,
    zoom: Int,
    canvasW: Float,
    canvasH: Float,
): Pair<Float, Float> {
    val n = 2.0.pow(zoom.toDouble())
    fun toTileF(la: Double, lo: Double): Pair<Double, Double> {
        val x = (lo + 180.0) / 360.0 * n
        val y = (1.0 - ln(tan(la * PI / 180.0) + 1.0 / cos(la * PI / 180.0)) / PI) / 2.0 * n
        return x to y
    }

    val (cx, cy) = toTileF(center.lat, center.lon)
    val (px, py) = toTileF(lat, lon)
    val scale = TILE_SIZE_PX.toFloat()
    return (
        canvasW / 2 + ((px - cx) * scale).toFloat() to
            canvasH / 2 + ((py - cy) * scale).toFloat()
    )
}

private fun center(size: Size) = Offset(size.width / 2, size.height / 2)

/** An arrow at the screen edge pointing to [at] when that point is off the round screen; nothing when it is visible. */
private fun DrawScope.drawOffscreenArrow(at: Offset, accentColor: Color) {
    val c = center(size)
    val edge = minOf(size.width, size.height) / 2 - 3.dp.toPx()
    val dx = at.x - c.x
    val dy = at.y - c.y
    val dist = kotlin.math.hypot(dx, dy)
    if (dist <= edge - 6.dp.toPx()) return // on the screen: the target marker is already visible
    val ux = dx / dist
    val uy = dy / dist
    val tip = Offset(c.x + ux * edge, c.y + uy * edge)
    val len = 16.dp.toPx()
    val half = 9.dp.toPx()
    val base = Offset(tip.x - ux * len, tip.y - uy * len)
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(base.x - uy * half, base.y + ux * half)
        lineTo(base.x + uy * half, base.y - ux * half)
        close()
    }
    drawPath(path, Color.Black.copy(alpha = 0.85f), style = Stroke(width = 5.dp.toPx(), join = StrokeJoin.Round))
    drawPath(path, accentColor)
}

/** [p] rotated clockwise by [degrees] around [pivot] (screen coordinates, y down). */
private fun rotatePoint(p: Offset, pivot: Offset, degrees: Float): Offset {
    val r = Math.toRadians(degrees.toDouble())
    val c = cos(r).toFloat()
    val s = sin(r).toFloat()
    val dx = p.x - pivot.x
    val dy = p.y - pivot.y
    return Offset(pivot.x + dx * c - dy * s, pivot.y + dx * s + dy * c)
}

/**
 * The new viewport centre after the finger dragged the map by ([dxPx], [dyPx]) on screen. The map is
 * drawn rotated clockwise by [rotationDeg], so the drag is first turned back into map pixels;
 * the content follows the finger, i.e. the centre moves the opposite way.
 */
internal fun panCenter(center: LatLon, dxPx: Float, dyPx: Float, zoom: Int, rotationDeg: Float): LatLon {
    val r = Math.toRadians(rotationDeg.toDouble())
    val mx = dxPx * cos(r) + dyPx * sin(r)
    val my = -dxPx * sin(r) + dyPx * cos(r)
    val n = 2.0.pow(zoom.toDouble())
    var x = (center.lon + 180.0) / 360.0 * n
    var y = (1.0 - ln(tan(center.lat * PI / 180.0) + 1.0 / cos(center.lat * PI / 180.0)) / PI) / 2.0 * n
    x -= mx / TILE_SIZE_PX
    y -= my / TILE_SIZE_PX
    val lon = (x / n * 360.0 - 180.0).coerceIn(-180.0, 180.0)
    val lat = (Math.atan(Math.sinh(PI * (1.0 - 2.0 * y / n))) * 180.0 / PI).coerceIn(-85.0, 85.0)
    return LatLon(lat, lon)
}
