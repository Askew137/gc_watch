package com.askew137.gcwatch.wear.ui.compass

import androidx.compose.ui.res.stringResource
import com.askew137.gcwatch.wear.R
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.askew137.gcwatch.wear.ui.UiPrefs
import com.askew137.gcwatch.wear.ui.components.IconButton
import androidx.wear.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Abc
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.OutlinedFlag
import com.askew137.gcwatch.core.model.AppConfig
import com.askew137.gcwatch.core.model.NavigationTarget
import com.askew137.gcwatch.core.model.Units
import com.askew137.gcwatch.wear.ui.theme.toComposeColor
import com.askew137.gcwatch.wear.gnss.GnssRepository
import com.askew137.gcwatch.wear.gnss.WatchLocation
import kotlin.math.roundToInt

/**
 * Full circular compass screen.
 *
 * Layout:
 * - Center: [CompassCanvas]
 * - Below canvas: distance text
 * - Left edge: GPS source toggle (top) + map mode (bottom)
 * - Right edge: cardinal toggle (top) + keep-screen-on (bottom)
 */
@Composable
fun CompassScreen(
    config: AppConfig,
    location: WatchLocation?,
    navigationTarget: NavigationTarget?,
    compassViewModel: CompassViewModel,
    gnssRepository: GnssRepository,
    onNavigateToMap: () -> Unit,
) {
    val context = LocalContext.current
    val azimuth by compassViewModel.azimuthDeg.collectAsStateWithLifecycle()
    val showCardinals by compassViewModel.showCardinals.collectAsStateWithLifecycle()
    val keepScreenOn by compassViewModel.keepScreenOn.collectAsStateWithLifecycle()
    val view = LocalView.current

    val accentColor = remember(config.accentColor) { config.accentColor.toComposeColor() }
    val targetBearing = navigationTarget?.bearingDeg ?: 0f

    // Hold the display on (full brightness, no ambient/dim) only while this screen is shown.
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    DisposableEffect(Unit) {
        compassViewModel.startSensor(context)
        onDispose { compassViewModel.stopSensor() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        // ── Compass canvas ───────────────────────────────────────────────────
        CompassCanvas(
            azimuthDeg = azimuth,
            targetBearingDeg = targetBearing,
            showCardinals = showCardinals,
            accentColor = accentColor,
            modifier = Modifier.fillMaxSize(),
        )

        // ── Distance label ───────────────────────────────────────────────────
        navigationTarget?.let { target ->
            Text(
                text = formatDistance(target.distanceM, config.units),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
            )
        }

        // ── Left edge: switch to the compass by itself when I reach the cache (on/off) ──────────
        var autoCompass by remember { mutableStateOf(UiPrefs.autoCompassAtTarget(context)) }
        var hint by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(hint) {
            if (hint != null) {
                kotlinx.coroutines.delay(1800)
                hint = null
            }
        }
        IconButton(
            onClick = {
                autoCompass = !autoCompass
                UiPrefs.setAutoCompassAtTarget(context, autoCompass)
                hint = context.getString(if (autoCompass) R.string.compass_auto_on else R.string.compass_auto_off)
            },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 4.dp)
                .offset(y = (-22).dp)
                .size(32.dp)
                .background(Color.Black.copy(alpha = 0.55f), CircleShape),
        ) {
            Icon(
                if (autoCompass) Icons.Rounded.Flag else Icons.Rounded.OutlinedFlag,
                contentDescription = stringResource(R.string.compass_auto_desc), modifier = Modifier.size(20.dp),
                tint = if (autoCompass) accentColor else Color.Gray,
            )
        }
        hint?.let {
            Text(
                text = it,
                fontSize = 11.sp,
                color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp)
                    .background(Color.Black.copy(alpha = 0.75f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }

        // ── Left edge: Navigate to map ────────────────────────────────────────
        IconButton(
            onClick = onNavigateToMap,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 4.dp)
                .offset(y = 22.dp)
                .size(32.dp)
                .background(Color.Black.copy(alpha = 0.55f), CircleShape),
        ) {
            Icon(Icons.Rounded.Map, contentDescription = stringResource(R.string.cd_map), modifier = Modifier.size(20.dp), tint = accentColor)
        }

        // ── Right edge: cardinal toggle ───────────────────────────────────────
        IconButton(
            onClick = { compassViewModel.toggleCardinals() },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp)
                .offset(y = (-22).dp)
                .size(32.dp)
                .background(Color.Black.copy(alpha = 0.55f), CircleShape),
        ) {
            Icon(
                Icons.Rounded.Abc,
                contentDescription = stringResource(R.string.cd_cardinals), modifier = Modifier.size(20.dp),
                tint = if (showCardinals) accentColor else Color.Gray,
            )
        }

        // ── Right edge: wakelock toggle ───────────────────────────────────────
        IconButton(
            onClick = { compassViewModel.toggleKeepScreenOn() },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp)
                .offset(y = 22.dp)
                .size(32.dp)
                .background(Color.Black.copy(alpha = 0.55f), CircleShape),
        ) {
            Icon(
                if (keepScreenOn) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                contentDescription = stringResource(R.string.cd_keep_screen_on), modifier = Modifier.size(20.dp),
                tint = if (keepScreenOn) accentColor else Color.Gray,
            )
        }
    }
}

internal fun formatDistance(distanceM: Float, units: Units): String {
    return if (units == Units.METRIC) {
        if (distanceM < 1000f) {
            "${distanceM.roundToInt()} m"
        } else {
            "${"%.1f".format(distanceM / 1000f)} km"
        }
    } else {
        val feet = distanceM * 3.28084f
        if (feet < 5280f) {
            "${feet.roundToInt()} ft"
        } else {
            val miles = feet / 5280f
            "${"%.1f".format(miles)} mi"
        }
    }
}
